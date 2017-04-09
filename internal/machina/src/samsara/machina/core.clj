(ns samsara.machina.core)

;;
;; TODO: managed transition
;; TODO: error management
;; TODO: auto-retry with exponential backoff
;; TODO: start and stop states
;;



(defn write-to-file [f]
  (spit f
        (str (java.util.Date.) \newline)
        :append true))


(comment
  (defn empty-machine
    "defines a empty state machine which only goes from start->stop"
    []
    {:state :machina/start

     :transitions {:machina/start [:machina/no-op :machina/stop]}})



  (defn configure-machine [sm fns]
    (-> sm
        ;; turn the list of states and transitions into a map
        (update :transitions (fn [ts]
                               (->> (map (fn [[s0 t s1]] [s0 [t s1]]) ts)
                                    (into {})))))))


;;(configure-machine (empty-machine))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;                  ---==| F I R S T   A T T E M P T |==----                  ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment

  (def sm
    {:state       :machina/start
     :data        nil
     :transitions
     {:machina/start {:fn*        (fnil inc 0)
                      :on-success :machina/start
                      :on-failure :machina/stop}}}
    )

  ;;
  ;; TODO: interesting approach but there is no way
  ;; for the `fn*` to know when to stop
  ;; or to `on-success` to decide which new
  ;; state to go. For example if the counter
  ;; gets to 10 I would like to exit
  ;; Same for the `on-failure` it should be
  ;; able to decide which state based on error
  ;; TODO: `on-success` and `on-failure` should
  ;; take the state in input `(on-success sm)`
  ;; or `(on-failure sm ex)`.
  ;;
  (defn play [{:keys [state transitions data] :as sm}]
    (if (= state :machina/stop)
      sm
      (if-let [{:keys [fn* on-success on-failure]} (get transitions state)]
        (try
          (let [data1 (apply fn* [data])]
            (assoc sm :data data1 :state on-success))
          (catch Exception x
            (assoc sm
                   :error {:from-state state
                           :error x}
                   :state on-failure)))
        (assoc sm :error {:from-state state
                          :error :machina/bad-state}))))


  (comment
    (iterate play sm)
    ))




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;                 ---==| S E C O N D   A T T E M P T |==----                 ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;



(comment



  ;; counter example
  (def sm
    {:state :machina/start
     :data   nil
     :transitions
     {:machina/start {:fn*        (fnil inc 0)
                      :on-success (fn [{:keys [data]}]
                                    (if (= data 10)
                                      :machina/stop
                                      :machina/start))
                      :on-failure (constantly :machina/stop)} }})

  (defn do! [f]
    (fn [v]
      (f v)
      v))


  (defn transition [{:keys [state transitions data] :as sm}]
    (if (= state :machina/stop)
      sm
      (if-let [{:keys [fn* on-success on-failure]} (get transitions state)]
        (try
          (as-> sm $
            (assoc $ :data  (apply fn* [data]))
            (assoc $ :state (on-success $)))
          (catch Exception x
            (as-> sm $
              (assoc $ :state (on-failure $))
              (dissoc $ :error))))
        (assoc sm :error {:from-state state
                          :error :machina/bad-state}))))




  (comment
    (transition sm)
    (->> sm
         (iterate transition)
         (take-while #(not= :machina/stop (:state %)))
         #_(map :data)
         last
         )
    )




  ;; write to file example
  (def sm
    {:state :machina/start
     :data   nil
     :transitions
     {:machina/start {:fn*        (constantly "/tmp/1/2/3/4/5/file.txt")
                      :on-success (constantly :write-to-file)
                      :on-failure (constantly :machina/stop)}


      :sleep {:fn*        (do! (fn [_] (Thread/sleep 1000)))
              :on-success (constantly :write-to-file)
              :on-failure (constantly :sleep)}


      :write-to-file {:fn*        (do! write-to-file)

                      :on-success (fn [sm] (println "OK")   (clojure.pprint/pprint sm) :sleep)
                      :on-failure (fn [sm] (println "FAIL") (clojure.pprint/pprint sm) :sleep)}}})


  (comment

    (-> sm
        transition
        transition
        )

    (->> sm
         (iterate transition)
         (take-while #(not= :machine/stop (:state %)))
         (last))



    )


  )




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;                  ---==| T H I R D   A T T E M P T |==----                  ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment

  ;; write to file example

  ;;
  ;; Most generic approach
  ;; but not much re-use.
  ;; also hard to make cross-cutting concerns
  ;; like a function which displays all
  ;; the state transitions or even
  ;; measure the time spent in each state.
  ;;
  (defmulti transition :state)

  (defmethod transition :machina/stop
    [sm]
    sm)


  (defmethod transition :machina/start
    [sm]
    (assoc sm
           :data "/tmp/1/2/3/4/5/file.txt"
           :state :write-to-file))


  (defmethod transition :write-to-file
    [{f :data :as sm}]
    (try
      (write-to-file f)
      (assoc sm :state :sleep)
      (catch Exception x
        (assoc sm :state :sleep))))


  (defmethod transition :sleep
    [sm]
    (Thread/sleep 1000)
    (assoc sm :state :write-to-file))

  (comment

    (-> sm
        transition
        transition
        transition
        )

    (->> sm
         (iterate transition)
         (take-while #(not= :machine/stop (:state %)))
         (last))



    ))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;                 ---==| F O U R T H   A T T E M P T |==----                 ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;
;; this time i'm going to use the same basic and generic idea of
;; a f(stm) -> stm' but without the multi-method dispatch
;; so that it is possible to add wrappers/filters style functions
;; which handle cross/cutting concerns.
;;

(comment

  (defn log-state-change [handler]
    (fn [sm1]
      (let [sm2 (handler sm1)]
        (println "transition: " (:state sm1) "->" (:state sm2))
        sm2)))

  (defn epoch-counter
    [handler]
    (fn [sm]
      (handler (update sm :epoch (fnil inc 0)))))

  (defn- wrapper
    ([] identity)
    ([f] f)
    ([f g] (g f))
    ([f g & fs]
     (reduce wrapper (concat [f g] fs))))

  (comment
    (defn mh [n]
      (fn [h]
        (fn [sm]
          (println n)
          (h sm))))

    (def f1 (mh 1))
    (def f2 (mh 2))
    (def f3 (mh 3))
    (def f4 (mh 4))

    ((wrapper identity f4 f3 f2 f1) {}))


  ;; write to file example
  (def sm
    {:state :machine/start
     :epoch 0
     :data nil

     :dispatch
     {:machine/stop identity
      :machine/start
      (fn
        [sm]
        (assoc sm
               :data "/tmp/1/2/3/4/5/file.txt"
               :state :write-to-file))

      :write-to-file
      (fn
        [{f :data :as sm}]
        (try
          (write-to-file f)
          (assoc sm :state :sleep)
          (catch Exception x
            (assoc sm :state :sleep))))

      :sleep
      (fn
        [sm]
        (Thread/sleep 1000)
        (assoc sm :state :write-to-file))}

     :wrappers
     [#'epoch-counter #'epoch-counter #'log-state-change]})


  (defn bad-state [sm]
    (throw (ex-info "Invalid state" sm)))

  ;; this seems to maintain the simplicity of
  ;; the earlier attempts with the general
  ;; approach of the multi-method
  ;; without scarifying the ability to
  ;; manage cross-cutting concerns.
  ;; note: are wrappers the best approach?
  ;; maybe something like pedestal stacklets
  ;; would be easier?

  (defn transition
    [{:keys [state data dispatch wrappers] :as sm}]
    (let [f0 (get dispatch state bad-state)
          f  (apply wrapper (cons f0 (reverse wrappers)))]
      (f sm)))


  (comment

    (-> sm
        transition
        transition
        ;;transition
        )

    (->> sm
         (iterate transition)
         (take-while #(not= :machine/stop (:state %)))
         (last))


    ))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;       ---==| E X P A N D I N G   F O U R T H   A T T E M P T |==----       ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;
;; same as before just now expanding the capabilities
;; of generic wrappers and common pattern/policies for
;; error handling (WORK IN PROGRESS)
;;

(comment

  (defn log-state-change [handler]
    (fn [sm1]
      (let [sm2 (handler sm1)]
        (println "transition: " (:state sm1) "->" (:state sm2))
        sm2)))

  (defn epoch-counter
    [handler]
    (fn [sm]
      (handler (update sm :epoch (fnil inc 0)))))

  ;; not sure about this
  (defn error-state
    [handler]
    (fn [sm]
      (try
        (handler sm)
        (catch Throwable x
          (assoc sm :last-error
                 {:from-state  (:state sm)
                  :error-epoch (:epoch sm)
                  :error       x}
                 :state :error)))))


  (defn- wrapper
    ([] identity)
    ([f] f)
    ([f g] (g f))
    ([f g & fs]
     (reduce wrapper (concat [f g] fs))))

  (comment
    (defn mh [n]
      (fn [h]
        (fn [sm]
          (println n)
          (h sm))))

    (def f1 (mh 1))
    (def f2 (mh 2))
    (def f3 (mh 3))
    (def f4 (mh 4))

    ((wrapper identity f4 f3 f2 f1) {}))


  ;; write to file example
  (def sm
    {:state :machine/start
     :epoch 0
     :data nil

     :dispatch
     {:machine/stop identity
      :machine/start
      (fn
        [sm]
        (assoc sm
               :data "/tmp/1/2/3/4/5/file.txt"
               :state :write-to-file))

      :write-to-file
      (fn
        [{f :data :as sm}]
        (write-to-file f)
        (assoc sm :state :sleep))

      :sleep
      (fn
        [sm]
        (Thread/sleep 1000)
        (assoc sm :state :write-to-file))}

     :error-policies
     {:machine/default {:type        :retry
                        :max-retry   :forever
                        :retry-delay [:random-exp-backoff :base 3000 :+/- 0.35 :max 25000]}

      :write-to-file   {:type        :retry
                        :max-retry   :forever
                        :retry-delay [:random 3000 :+/- 0.35]}}

     :wrappers
     [#'epoch-counter #'log-state-change #'error-state]})


  (defn bad-state [sm]
    (throw (ex-info "Invalid state" sm)))

  (defn transition
    [{:keys [state data dispatch wrappers] :as sm}]
    (let [f0 (get dispatch state bad-state)
          f  (apply wrapper (cons f0 (reverse wrappers)))]
      (f sm)))


  (comment

    (-> sm
        transition
        transition
        ;;transition
        )

    (->> sm
         (iterate transition)
         (take-while #(not= :machine/stop (:state %)))
         (last))


    ))
