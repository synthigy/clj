;; Synthigy Chat — babashka BFF (real-time, Tyrell UI).
;;
;; • RLS  — one confidential `chat-bff` client + `acting_as` per user.
;; • LIVE — one server-side `syn/listen` subscription on Chato Message;
;;          every change broadcasts a Datastar SSE patch to all open tabs,
;;          each re-fetched RLS-scoped via acting_as (notify-then-refetch).
;; • UI   — Tyrell web components (<ty-*>) + Datastar, both via CDN. No build.
;; • /history powers the edit trail + deleted-message recovery.
;;
;; Run:  bb main.clj   → http://localhost:8095
;; Open two tabs as different users (?user=alice / ?user=bob) and watch a
;; message sent in a shared group appear in both, live.

(require '[org.httpkit.server :as http]
         '[hiccup2.core :as h]
         '[hiccup.util :as hu]
         '[clojure.string :as str]
         '[babashka.json :as json]
         '[tyrell.lucide :as L]                ; icon SVGs from the dev.gersak/tyrell-icons jar
         '[synthigy.client :as syn]
         ;; generated from synthigy/chat.xsql — regenerate with `bb gen`
         '[chat.ops.message :as message]
         '[chat.ops.group :as group])

;; Real Tyrell icons: each tyrell.lucide var IS the full <svg> string. Slot it
;; into <ty-icon> — renders server-side, sizes via ty-icon, survives morphs.
(def lucide {"send" L/send "pencil" L/pencil "trash" L/trash-2 "clock" L/clock
             "sun" L/sun "moon" L/moon "chat" L/message-circle "x" L/x})

(defn icon [nm & {:keys [size cls] :or {size "sm"}}]
  [:ty-icon (cond-> {:size size} cls (assoc :class cls))
   (hu/raw-string (lucide nm))])

;; ── Config / cast (seeded Synthigy Chat data) ──────────────────────────────

(def endpoint "http://localhost:7887")
(def content-attr "BXRMomJBecJo2uAuHELmba")

(def groups {"Vv6XbBQ8mcyHPr54WuBsDW" "Croatia"
             "W5TXQSsYByDUThFjB4m91d" "Family"
             "m1sbnPfCPTBDZKfQR4QHVt" "Friends"})

(def users
  {"alice"   {:xid "9PNqPf2Zxut5C5VfkLRBCY" :eid 7617 :name "alice"
              :groups ["Vv6XbBQ8mcyHPr54WuBsDW" "W5TXQSsYByDUThFjB4m91d"]}
   "bob"     {:xid "4CHTc3TfcapaJfd9S1RQ1W" :eid 7619 :name "bob"
              :groups ["Vv6XbBQ8mcyHPr54WuBsDW" "m1sbnPfCPTBDZKfQR4QHVt"]}
   "charlie" {:xid "V4vgXcTpy1kss5qEoLQeQ6" :eid 7620 :name "charlie"
              :groups ["W5TXQSsYByDUThFjB4m91d"]}
   "diana"   {:xid "FqNTmRXVLX345MLSJvmWXC" :eid 7621 :name "diana"
              :groups ["Vv6XbBQ8mcyHPr54WuBsDW" "W5TXQSsYByDUThFjB4m91d" "m1sbnPfCPTBDZKfQR4QHVt"]}})

(def eid->name (into {} (map (fn [[_ u]] [(:eid u) (:name u)]) users)))

(syn/connect! {:endpoint endpoint
               :client-id "chat-bff" :client-secret "chat-bff-secret"})

(def author-attr "UCDXVYST8HaQWVjEbAUJhw")      ; active Chato Message.Author attr xid
(defonce deleted-log (atom []))                 ; [{:xid :groups [xid…]}]
(defonce author-cache (atom {}))                ; msg-xid → author eid
(defonce conn->wq (atom {}))                     ; SSE channel → its watch-query (lifecycle only)
(def msg-selection {:content nil :published-on nil})

;; user-type attrs aren't projectable via /data selection in this model
;; (Author expands to a from-label-less reference relation). Read the author
;; from the audit plug instead — cached, and set directly on send.
;; ponytail: history-backed author lookup; drop if the engine learns to
;; project user-type attributes.
(defn author-eid [xid]
  (or (get @author-cache xid)
      (let [e (try (get (syn/history-get-at
                         xid (str (java.time.Instant/now)) :include-deleted? true)
                        (keyword author-attr))
                   (catch Exception _ nil))]
        (when e (swap! author-cache assoc xid e))
        e)))

;; ── Data (all RLS-scoped via acting_as) ────────────────────────────────────

(defn list-messages [user-key]
  (->> (message/list {} :acting-as (:xid (users user-key)))
       (sort-by :published-on)))

;; The m2m message↔group link isn't projectable from Chato Message, but it IS
;; readable from the User Group side. We read it once as a SUPERUSER service
;; user (objective membership, same for everyone) and invert to msg-xid → #{group-xid}.
;; Per-user views stay RLS-correct because message *content* comes from the
;; acting_as <user> search above; this map only labels/filters what they already see.
(def superuser-svc "E5YqEfedWg7S1b2MPzwJQX")    ; chat-bff-svc (SUPERUSER)

(defn group-map []
  (let [gs (group/memberships {:xids (vec (keys groups))}
                              :acting-as superuser-svc)]
    (reduce (fn [m g]
              (reduce (fn [m msg] (update m (:xid msg) (fnil conj #{}) (:xid g)))
                      m (:messages g)))
            {} gs)))

(defn send-message! [user-key group-xid content]
  (let [u (users user-key)]
    (when-not (str/blank? content)
      (let [r (syn/sync "Chato Message"
                        {:content content :author (:eid u)
                         :published-on (str (java.time.Instant/now))
                         :user-groups [{:xid group-xid}]}
                        :acting-as (:xid u))]
        (swap! author-cache assoc (:xid r) (:eid u))   ; known author, no audit lookup needed
        r))))

(defn edit-message! [user-key xid content]
  (syn/sync "Chato Message" {:xid xid :content content}
            :acting-as (:xid (users user-key))))

(defn delete-message! [user-key xid groups]
  (syn/delete "Chato Message" {:xid xid} :acting-as (:xid (users user-key)))
  (swap! deleted-log conj {:xid xid :groups groups}))

(defn content-history [xid]
  (->> (syn/history-events xid)
       (filter #(= content-attr (:attribute-xid %)))
       (map (fn [e] {:ts (:ts e) :value (:value e)}))
       (sort-by :ts)))

(defn recover-deleted [xid]
  (-> (syn/history-get-at xid (str (java.time.Instant/now)) :include-deleted? true)
      (get (keyword content-attr))))

;; ── Fragments (Tyrell components) ──────────────────────────────────────────

(defn time-str [t] (some-> t str (#(subs % 0 (min 19 (count %)))) (str/replace "T" " ")))

(def avatar-bg {"alice" "ty-bg-primary" "bob" "ty-bg-success"
                "charlie" "ty-bg-warning" "diana" "ty-bg-accent"})

(defn avatar [name size]
  [:div.shrink-0.rounded-full.grid.place-items-center.font-semibold.select-none
   {:class (str (avatar-bg name "ty-bg-neutral") " " size)}
   (str/upper-case (subs (str name) 0 1))])

(defn msg-row [user-key gmap {:keys [xid content published-on]}]
  (let [aeid  (author-eid xid)
        name  (eid->name aeid "?")
        mine? (= (:eid (users user-key)) aeid)]
    [:div.group.flex.items-start.gap-2 {:class (when mine? "flex-row-reverse")}
     (avatar name "w-8 h-8 text-xs")
     [:div.flex.flex-col.gap-1.bw {:class (if mine? "items-end" "items-start")}
      [:div.flex.items-center.gap-2.text-xs.px-1
       [:span.font-semibold.ty-text+ name]
       (for [gx (sort (get gmap xid))]
         [:ty-tag {:size "xs" :flavor "secondary"} (groups gx "?")])
       [:span.ty-text-- (time-str published-on)]]
      [:div.px-3.py-1.rounded-2xl.t13.leading-snug.shadow-sm.break-words
       {:class (if mine?
                 "ty-bg-primary ty-text-primary++ rounded-br-sm"
                 "ty-elevated rounded-bl-sm")}
       content]
      [:div.flex.gap-1.opacity-0.group-hover:opacity-100.transition-opacity
       [:ty-button {:size "xs" :appearance "ghost" :flavor "neutral" :title "History"
                    "data-on:click" (str "@get('/history?user=" user-key "&msg=" xid "')")} (icon "clock" :size "xs")]
       (when mine?
         (list
          [:ty-button {:size "xs" :appearance "ghost" :flavor "neutral" :title "Edit"
                       "data-on:click" (str "@get('/edit-form?user=" user-key "&msg=" xid "')")} (icon "pencil" :size "xs")]
          [:ty-button {:size "xs" :appearance "ghost" :flavor "danger" :title "Delete"
                       "data-on:click" (str "@post('/delete?user=" user-key "&msg=" xid
                                            "&groups=" (str/join "," (get gmap xid)) "')")} (icon "trash" :size "xs")]))]]]))

(defn deleted-panel [user-key]
  (let [u (users user-key)
        mine (filter #(some (set (:groups u)) (:groups %)) @deleted-log)]
    (when (seq mine)
      [:div.mt-2.pt-3.border-t.ty-border-
       [:div.flex.items-center.gap-1.t11.ty-text--.mb-2.px-1
        (icon "trash" :size "xs") "recently deleted — recovered from /history"]
       [:div.flex.flex-col.gap-2
        (for [{:keys [xid]} (reverse mine)]
          [:div.flex.items-center.gap-2.opacity-60
           [:div.shrink-0.w-8.h-8.rounded-full.grid.place-items-center.ty-bg-neutral-.ty-text-
            (icon "trash" :size "xs")]
           [:div.px-3.py-1.rounded-2xl.ty-elevated.t13.italic.ty-text--.line-through
            (or (recover-deleted xid) "(unrecoverable)")]])]])))

;; Render #messages from a vector of rows (the watch-query value). No state —
;; the rows ARE the DB result; nothing is held client- or BFF-side. `group`
;; ("all" or a group xid) narrows the view to one group's whole conversation.
(defn render-messages [user-key group rows]
  (let [gmap (group-map)
        rows (cond->> (sort-by :published-on rows)
               (and group (not= group "all"))
               (filter #(contains? (get gmap (:xid %)) group)))]
    [:div#messages.flex.flex-col.gap-4.px-4.py-5
     (if (seq rows)
       (for [m rows] (msg-row user-key gmap m))
       [:div.ty-text--.text-sm.text-center.py-10 "No messages in this view yet."])
     (deleted-panel user-key)
     ;; bottom sentinel — id unique per RENDER (not per count, so edits and
     ;; refetches fire too) → Datastar re-runs data-init after every patch and
     ;; scrolls the ty-scroll-container to the latest message. MUST stay a
     ;; single expression (datastar compiles the value as `return (...)`);
     ;; `data-on:load`/`data-on-load` are DEAD in datastar v1 RC — data-init
     ;; is the on-mount hook.
     [:div {:id (str "end-" (System/currentTimeMillis))
            "data-init" "el.closest('ty-scroll-container')?.scrollToBottom?.(false)"}]]))

(defn messages-fragment [user-key group]
  (render-messages user-key group (list-messages user-key)))

(defn compose [user-key group]
  ;; You can only send into a specific group. In the "All groups" view there's
  ;; no single target, so we show a hint instead of an input.
  (if (or (nil? group) (= group "all"))
    [:div#compose.px-4.pt-3.pb-5.shrink-0.border-t.ty-border-.text-center.t13.ty-text--
     "Pick a group above to send a message."]
    [:div#compose.px-4.pt-3.pb-5.shrink-0.border-t.ty-border-
     {"data-signals" "{content:''}"}
     [:div.flex.gap-2.items-end
      [:ty-textarea {"data-bind" "content" :class "flex-1" :size "sm" :rows "1" :resize "none"
                     :min-height "38px" :max-height "140px"
                     :placeholder (str "Message #" (groups group group) "…  ·  Enter to send")
                     "data-on:keydown"
                     (str "evt.key==='Enter' && !evt.shiftKey "
                          "&& (evt.preventDefault(), @post('/send?user=" user-key "&group=" group "'))")}]
      [:div.flex {:style {:height "50px"}}
       [:ty-button {:flavor "primary" :pill true :size "sm" :class "shrink-0" :title "Send"
                    "data-on:click" (str "@post('/send?user=" user-key "&group=" group "')")}
        (icon "send" :size "sm")]]]]))

;; segmented user toggle (avatar pills)
(defn user-switcher [active]
  [:div.flex.items-center.gap-1.p-1.rounded-xl.ty-content
   (for [[k u] (sort-by key users)]
     [:button.flex.items-center.gap-2.pl-1.pr-3.py-1.rounded-lg.transition
      {:class (if (= k active)
                "ty-bg-primary ty-text-primary++ shadow-sm"
                "ty-text- hover:ty-text")
       "data-on:click" (str "window.location='/?user=" k "'")}
      (avatar k "w-6 h-6 text-xs")
      [:span.text-sm.font-medium (:name u)]])])

;; segmented group filter — "All groups" + each group the user manages
(defn group-tabs [user-key active-group]
  (let [u (users user-key)
        opts (cons ["all" "All groups"] (map (fn [g] [g (groups g)]) (:groups u)))]
    [:div.flex.items-center.gap-1.p-1.rounded-xl.ty-content
     (for [[gid label] opts]
       [:button.px-3.py-1.text-sm.rounded-lg.font-medium.transition
        {:class (if (= gid (or active-group "all"))
                  "ty-bg-secondary ty-text-secondary++ shadow-sm"
                  "ty-text- hover:ty-text")
         "data-on:click" (str "window.location='/?user=" user-key "&group=" gid "'")}
        label])]))

(defn page [user-key group]
  (str "<!doctype html>"
       (h/html
        [:html {:class "dark"}
         [:head
          [:meta {:charset "utf-8"}]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
          [:title "Synthigy Chat"]
          [:script {:src "https://cdn.tailwindcss.com"}]                                 ; layout/spacing
          [:link {:rel "stylesheet" :href "https://cdn.jsdelivr.net/npm/tyrell-components@1.0.0-TC31/css/tyrell.css"}]  ; colors/surfaces
          [:script {:type "module" :src "https://cdn.jsdelivr.net/npm/tyrell-components@1.0.0-TC31/dist/tyrell.js"}]
          [:script {:type "module" :src "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.2/bundles/datastar.js"}]
          [:style "#panel:empty{display:none}
                   ty-input,ty-dropdown,ty-textarea{--ty-input-border:var(--ty-border)}
                   /* sugar-safe aliases for Tailwind arbitraries (can't live in a hiccup tag) */
                   .t13{font-size:13px}.t11{font-size:11px}.bw{max-width:78%}"]]
         [:body.ty-canvas.ty-text.t13.h-screen.flex.flex-col.overflow-hidden
          {"data-signals" "{editId:'', editText:''}"}
          ;; ── header: brand · user toggle · theme · group tabs ──
          [:header.ty-elevated.border-b.ty-border-.px-5.py-2.flex.flex-col.gap-2.shrink-0
           [:div.flex.items-center.gap-4
            [:div.flex.items-center.gap-2
             [:div.w-7.h-7.rounded-lg.ty-bg-primary.ty-text-primary++.grid.place-items-center
              (icon "chat" :size "sm")]
             [:div.flex.flex-col.leading-tight
              [:span.font-semibold.ty-text++.text-sm "Synthigy Chat"]
              [:span.t11.ty-text-- "RLS · history · live"]]]
            [:div.ml-auto (user-switcher user-key)]
            [:button.ty-text-.hover:ty-text.ml-1
             {:title "Toggle theme"
              "data-on:click" "document.documentElement.classList.toggle('dark')"}
             (icon "sun" :cls "hidden dark:block") (icon "moon" :cls "block dark:hidden")]]
           [:div.flex.items-center.gap-3
            [:span.text-xs.ty-text--.uppercase.tracking-wide "Group"]
            (group-tabs user-key group)]]
          ;; ── chat column ── (data-init opens the live DB-backed stream;
          ;; NB: datastar v1 RC removed `data-on-load` — `data-init` is the
          ;; on-mount hook. Retry ~forever with capped backoff so a BFF
          ;; restart doesn't leave the tab permanently deaf (default gives
          ;; up after 10 attempts).
          [:main.flex-1.flex.flex-col.min-h-0.w-full.max-w-3xl.mx-auto
           {"data-init" (str "@get('/stream?user=" user-key "&group=" (or group "all")
                             "', {retryMaxCount: 1000000, retryMaxWaitMs: 10000})")}
           ;; relative flex box → ty-scroll-container fills it absolutely, so its
           ;; height is DEFINITE (= the box), which is what .scroll-wrapper's
           ;; height:100% needs. Resizes with the flex box; no JS.
           [:div.relative.flex-1.min-h-0
            [:ty-scroll-container.absolute.inset-0 {:custom-scrollbar "true"}
             (messages-fragment user-key group)]]
           ;; edit bar (shown only while editing)
           [:div.ty-floating.border-t.ty-border.px-4.py-3.flex.gap-2.items-end.shrink-0
            {"data-show" "$editId != ''"}
            [:span.text-xs.ty-text--.self-center "editing"]
            [:ty-input {"data-bind" "editText" :class "flex-1" :flavor "neutral"}]
            [:ty-button {:flavor "primary" :size "sm" "data-on:click" (str "@post('/edit?user=" user-key "')")} "Save"]
            [:ty-button {:flavor "neutral" :size "sm" :appearance "outlined" "data-on:click" "$editId=''"} "Cancel"]]
           (compose user-key group)]
          ;; history popover (patched in by /history)
          [:div#panel]]])))

(defn history-panel [_user-key xid]
  [:div#panel.fixed.bottom-24.right-6.z-50.w-80.ty-floating.rounded-xl.shadow-xl.p-4
   [:div.flex.items-center.gap-1.mb-3
    (icon "clock" :size "sm" :cls "ty-text-primary")
    [:span.font-semibold.ty-text+.text-sm "Edit history"]
    [:button.ml-auto.ty-text-.hover:ty-text {"data-on:click" "@get('/clear-panel')"}
     (icon "x" :size "xs")]]
   (let [vs (content-history xid)]
     (if (seq vs)
       [:div.flex.flex-col.gap-2
        (for [{:keys [ts value]} vs]
          [:div.border-l-2.ty-border-primary.pl-2
           [:div.t11.ty-text-- (time-str ts)]
           [:div.ty-text.t13 value]])]
       [:p.t13.ty-text-- "No history yet (audit drain may lag a moment)."]))])

;; ── Datastar SSE ───────────────────────────────────────────────────────────

(defn frag->html [hiccup] (str (h/html hiccup)))

(defn patch-elements [hiccup]
  ;; SSE framing: a data value may not contain raw newlines — multiline HTML
  ;; (e.g. a chat message with line breaks) must be split into one
  ;; `data: elements <line>` per line or everything after the first newline
  ;; is silently dropped and the patch arrives truncated.
  (str "event: datastar-patch-elements\n"
       (->> (str/split-lines (frag->html hiccup))
            (map #(str "data: elements " %))
            (str/join "\n"))
       "\n\n"))

(defn patch-signals [m]
  (str "event: datastar-patch-signals\ndata: signals " (json/write-str m) "\n\n"))

(def sse-headers {"Content-Type" "text/event-stream" "Cache-Control" "no-cache"})

(defn sse-response [& events]
  {:status 200 :headers sse-headers :body (apply str events)})

;; ── HTTP ─────────────────────────────────────────────────────────────────

(defn signals [req]
  (let [raw (some-> (:body req) slurp)]
    (when-not (str/blank? raw) (json/read-str raw))))

(defn qp [qs]
  (when qs (into {} (for [p (str/split qs #"&") :let [[k v] (str/split p #"=" 2)]]
                      [k (some-> v (java.net.URLDecoder/decode "UTF-8"))]))))

;; Each open tab gets a live, RLS-scoped watch-query (a DB-backed atom). We
;; add-watch it and push whenever the DB changes — there is NO server-pushed
;; broadcast and NO client/BFF message state. A write anywhere → the SDK's SSE
;; subscription fires → this user's watch-query re-pulls (RLS via acting_as) →
;; add-watch → patch. Pure "all DB".
(defn stream [req user-key group]
  (let [u   (users user-key)
        uid (:xid u)
        ;; Room-scoped subscription: instead of entity-wide "any Chato Message
        ;; changed", watch the GROUP record(s) — link events fire when a
        ;; message attaches to that room. :track-rows keeps the visible
        ;; messages' own xids in the interest so edits/deletes fire too.
        rooms (if (= group "all") (:groups u) [group])]
    (http/as-channel req
                     {:on-open  (fn [ch]
                                  (http/send! ch {:status 200 :headers sse-headers} false)
                                  (let [wq (message/watch-list {}
                                                               :acting-as uid
                                                               :entity-track false
                                                               :records rooms
                                                               :track-rows true)
                                        push (fn [rows]
                                               (try (http/send! ch (patch-elements (render-messages user-key group rows)) false)
                                                    (catch Throwable _ (syn/close-watch! wq))))]
                                    (swap! conn->wq assoc ch wq)
                                    (push @wq)                                   ; initial paint, from the DB
                                    (add-watch wq ch (fn [_ _ _ rows] (push rows)))))   ; live, on DB change
                      :on-close (fn [ch _]
                                  (when-let [wq (get @conn->wq ch)]
                                    (syn/close-watch! wq)
                                    (swap! conn->wq dissoc ch)))})))

(defn handler [{:keys [uri request-method query-string] :as req}]
  (let [q (qp query-string)
        user (or (get q "user") "alice")
        group (or (get q "group") "all")]
    (case [request-method uri]
      [:get "/"]            {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"}
                             :body (page user group)}
      [:get "/stream"]      (stream req user group)
      [:get "/history"]     (sse-response (patch-elements (history-panel user (get q "msg"))))
      [:get "/clear-panel"] (sse-response (patch-elements [:div#panel]))
      ;; edit: server round-trip avoids escaping content into client JS — load
      ;; the current text into the edit signals (RLS-scoped fetch).
      [:get "/edit-form"]   (let [xid (get q "msg")
                                  m (->> (list-messages user) (filter #(= xid (:xid %))) first)]
                              (sse-response (patch-signals {:editId xid :editText (or (:content m) "")})))
      ;; Writes just hit the DB. The list refreshes itself on every open tab via
      ;; that tab's watch-query → add-watch (above). No broadcast, no patch here.
      [:post "/send"]       (let [s (signals req)]   ; group comes from the query (compose posts ?group=)
                              (send-message! user (or (get q "group") (:group s)) (:content s))
                              (sse-response (patch-signals {:content ""})))   ; just clear the input
      [:post "/edit"]       (let [s (signals req)]
                              (edit-message! user (:editId s) (:editText s))
                              (sse-response (patch-signals {:editId "" :editText ""})))
      [:post "/delete"]     (do (delete-message! user (get q "msg")
                                                 (remove str/blank? (str/split (or (get q "groups") "") #",")))
                                (sse-response))
      {:status 404 :body "not found"})))

(defonce server (atom nil))
(defn -main []
  (when @server (@server))
  (reset! server (http/run-server #'handler {:port 8095}))
  (println "Synthigy Chat BFF → http://localhost:8095 (per-connection watch-query; all DB)"))

;; Auto-run only when executed as a script (`bb main.clj`). When loaded in an
;; nREPL (load-file), this is skipped — call (-main) yourself; the nREPL keeps
;; the process alive, so no blocking @(promise) is needed.
;; Script mode also opens an nREPL (:1667) so a running BFF can be inspected
;; live — e.g. the watch multiplexer: (:watches @(:mux synthigy.client/*client*)).
(when (= *file* (System/getProperty "babashka.file"))
  (-main)
  ((requiring-resolve 'babashka.nrepl.server/start-server!) {:host "127.0.0.1" :port 1667})
  (spit ".nrepl-port" "1667")
  @(promise))
