(ns groundwork-short-codes.postgres-test
  "The two functions in Postgres, declared through groundwork-postgres as an
   app declares them: migrated, filled on insert, unique, kept on update,
   under row-level security. Runs when POSTGRES_TEST_PORT is set (see
   README.md); each test starts from an empty database."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [groundwork-logs.api :as logs]
   [groundwork-metrics.api :as metrics]
   [groundwork-postgres.api :as postgres]
   [groundwork-settings.api :as settings]
   [groundwork-short-codes.api :as short-codes])
  (:import
   (java.sql SQLException)))

(def ^:private port (some-> (System/getenv "POSTGRES_TEST_PORT") parse-long))

(defn- make-pool-settings []
  {:host "localhost" :port port
   :database (or (System/getenv "POSTGRES_TEST_DATABASE") "postgres")
   :user (System/getenv "POSTGRES_TEST_USER")
   :password (some->> (System/getenv "POSTGRES_TEST_PASSWORD") (settings/make-secret :groundwork-postgres/password))
   :tls :off :tls-root-cert nil :pool-size 4
   :connection-wait-ms 30000 :connection-check-wait-ms 5000 :connection-max-lifetime-ms 1800000
   :connection-keepalive-every-ms 120000 :leak-warning-after-ms :none :connect-timeout-seconds 10
   :read-timeout-seconds :none :tcp-keepalive? false :statement-timeout-ms 60000 :lock-timeout-ms :none
   :idle-in-transaction-timeout-ms :none :time-zone "UTC"
   :metric-buckets {:query-seconds [0.01 1] :connection-acquire-seconds [0.01 1] :connection-usage-seconds [0.01 1]}})

(def ^:private log (fn [_level _event _data] nil))

(defn- make-meter []
  (metrics/make-meter {:jvm-metrics false :most-series-per-metric 1000}
                      {:log (logs/log-function (logs/make-logger {:lowest-level :error :format :json :errors-kept 1
                                                                  :write-to (fn [_])}))}))

(defn- run-sql! [on text]
  (postgres/run-statement! on (postgres/make-query-from-sql-text "test" text) {}))

(defn- find-rows [on text]
  (postgres/find-rows on (postgres/make-query-from-sql-text "test" text) {}))

(defn- make-table
  "A table with a short code: :letter, :random-length, and :gates (nil for
   no row-level security)."
  [table-key entity letter random-length gates]
  (let [column (keyword entity "short-code")]
    {:table      table-key
     :columns    [[(keyword entity "id") :uuid {:primary-key? true :default :random-uuid}]
                  [(keyword entity "shop") :string]
                  [(keyword entity "quantity") :int {:default 1}]
                  [column :string {:unique? true}]]
     :indexes    []
     :uniques    []
     :checks     []
     :references []
     :triggers   [(short-codes/make-trigger {:letter        letter
                                             :column-name   (postgres/turn-key-into-column-name column)
                                             :random-length random-length})]
     :gates      gates
     :on-change  nil}))

(def ^:private shop-gate "%s__shop = current_setting('test.shop', true)")

(def ^:private tables
  [(make-table :batches "batch" "B" 7 nil)
   (make-table :orders "order" "O" 5 nil)
   (make-table :racks "rack" "R" 7 (let [gate (format shop-gate "rack")]
                                     {:read gate :insert gate :update gate :delete gate}))])

(def ^:private declared
  {:tables tables :extensions [] :provider-extensions [] :functions short-codes/function-files :views []})

(def ^:private migrate-options
  {:instant-part {:statement-timeout-ms 10000 :lock-timeout-ms 5000}
   :slow-part    {:statement-timeout-ms :none :lock-timeout-ms 5000}
   :log          log})

(defn- with-migrated-database [f]
  (let [pool (postgres/start-pool! (make-pool-settings) {:log log :meter (make-meter)})]
    (try
      (run-sql! pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS groundwork_postgres CASCADE")
      @(:slow-part (postgres/migrate! pool declared [] migrate-options))
      (f pool)
      (finally (postgres/stop-pool! pool)))))

(defmacro ^:private when-postgres [test-name & body]
  `(if-not port
     (println ~(str "groundwork-short-codes." test-name ": skipped, POSTGRES_TEST_PORT is not set"))
     (with-migrated-database (fn [~'pool] ~@body))))

(defn- codes-of [pool table entity]
  (mapv (keyword entity "short-code")
        (find-rows pool (format "SELECT %s__short_code FROM %s" entity table))))

(defn- code-pattern [letter random-length]
  (re-pattern (str "^" letter "[" short-codes/alphabet "]{" random-length "}$")))

(defn- sql-state [f]
  (try (f) nil
       (catch SQLException e (.getSQLState e))))

(deftest groundwork-postgres-migrates-the-functions-and-triggers-and-then-has-nothing-to-do
  (when-postgres "postgres-test"
    (let [plan (postgres/make-plan-from-tables pool declared [])]
      (is (empty? (:stops plan)) (pr-str (:stops plan)))
      (is (empty? (:instant plan)) "a second migrate finds the functions and triggers as declared")
      (is (empty? (:slow plan))))
    (is (= 3 (count (find-rows pool "SELECT tgname::text AS trigger__name FROM pg_trigger WHERE NOT tgisinternal"))))))

(deftest a-row-inserted-without-a-code-gets-one-that-fits
  (when-postgres "postgres-test"
    (run-sql! pool "INSERT INTO batches (batch__shop) VALUES ('a')")
    (run-sql! pool "INSERT INTO orders (order__shop) VALUES ('a')")
    (let [[batch-code] (codes-of pool "batches" "batch")
          [order-code] (codes-of pool "orders" "order")]
      (is (re-matches (code-pattern "B" 7) batch-code) batch-code)
      (is (re-matches (code-pattern "O" 5) order-code) order-code)
      (is (= batch-code (short-codes/read-code batch-code)) "the api reads it as stored"))))

(deftest a-row-inserted-with-a-code-keeps-it
  (when-postgres "postgres-test"
    (run-sql! pool "INSERT INTO batches (batch__shop, batch__short_code) VALUES ('a', 'BKEEPME2')")
    (is (= ["BKEEPME2"] (codes-of pool "batches" "batch")))))

(deftest one-statement-of-many-rows-gives-each-its-own-code
  (when-postgres "postgres-test"
    (run-sql! pool "INSERT INTO batches (batch__shop) SELECT 'a' FROM generate_series(1, 5000)")
    (let [codes (codes-of pool "batches" "batch")]
      (is (= 5000 (count codes) (count (set codes))))
      (is (every? #(re-matches (code-pattern "B" 7) %) codes)))))

(deftest every-character-of-the-alphabet-comes-about-equally-often
  (when-postgres "postgres-test"
    (run-sql! pool "INSERT INTO batches (batch__shop) SELECT 'a' FROM generate_series(1, 20000)")
    (let [counts   (frequencies (mapcat rest (codes-of pool "batches" "batch")))
          expected (/ (* 20000 7) 31.0)]
      (is (= (set short-codes/alphabet) (set (keys counts))) "all 31, and nothing else")
      ;; 140000 draws over 31: a fair character stays well within 10% of
      ;; 4516 (its standard deviation is about 66)
      (is (every? #(< (Math/abs (- % expected)) (* 0.1 expected)) (vals counts)) (pr-str counts)))))

(deftest a-copy-of-a-code-is-refused-by-the-unique-index
  (when-postgres "postgres-test"
    (run-sql! pool "INSERT INTO batches (batch__shop, batch__short_code) VALUES ('a', 'BCOPYME2')")
    (is (= "23505" (sql-state #(run-sql! pool "INSERT INTO batches (batch__shop, batch__short_code) VALUES ('b', 'BCOPYME2')"))))
    (testing "a copy that leaves the code out gets a new one"
      (run-sql! pool "INSERT INTO batches (batch__shop, batch__quantity) SELECT batch__shop, batch__quantity FROM batches")
      (is (= 2 (count (set (codes-of pool "batches" "batch"))))))))

(deftest an-update-never-changes-the-code
  (when-postgres "postgres-test"
    (run-sql! pool "INSERT INTO batches (batch__shop) VALUES ('a')")
    (let [[before] (codes-of pool "batches" "batch")]
      (run-sql! pool "UPDATE batches SET batch__quantity = 7")
      (is (= [before] (codes-of pool "batches" "batch"))))))

(deftest the-code-is-required
  (when-postgres "postgres-test"
    (run-sql! pool "INSERT INTO batches (batch__shop) VALUES ('a')")
    (is (= "23502" (sql-state #(run-sql! pool "UPDATE batches SET batch__short_code = NULL"))) "NOT NULL")))

(defn- as-shop! [pool shop f]
  (postgres/run-in-transaction! pool {:read-only? false :log nil}
                                (fn [tx]
                                  (run-sql! tx (format "SET LOCAL test.shop = '%s'" shop))
                                  (f tx))))

(deftest under-row-level-security-the-code-is-filled-and-unique-across-rows-one-cannot-see
  (when-postgres "postgres-test"
    ;; a role is the cluster's, not the database's: made again each run
    (run-sql! pool "DROP ROLE IF EXISTS short_codes_app; CREATE ROLE short_codes_app NOLOGIN")
    (run-sql! pool "GRANT USAGE ON SCHEMA public TO short_codes_app; GRANT SELECT, INSERT, UPDATE ON racks TO short_codes_app")
    (let [as-app!   (fn [shop f] (as-shop! pool shop (fn [tx] (run-sql! tx "SET LOCAL ROLE short_codes_app") (f tx))))
          insert!   (fn [shop code-sql] (as-app! shop #(run-sql! % (format "INSERT INTO racks (rack__shop, rack__short_code) VALUES ('%s', %s)" shop code-sql))))]
      (insert! "a" "DEFAULT")
      (insert! "b" "DEFAULT")
      (let [a-codes (as-app! "a" #(codes-of % "racks" "rack"))
            b-codes (as-app! "b" #(codes-of % "racks" "rack"))]
        (is (= 1 (count a-codes) (count b-codes)) "each shop sees only its own")
        (is (re-matches (code-pattern "R" 7) (first a-codes)))
        (is (= "23505" (sql-state #(insert! "b" (str "'" (first a-codes) "'"))))
            "the unique index sees the row shop b can't")))))

(defn- in-transaction! [pool f]
  (postgres/run-in-transaction! pool {:read-only? false :log nil} f))

(deftest a-code-clash-is-run-again-with-new-codes-and-the-transaction-goes-on
  (when-postgres "postgres-test"
    (run-sql! pool "INSERT INTO batches (batch__shop, batch__short_code) VALUES ('a', 'BTAKEN22')")
    (let [calls (atom 0)]
      (in-transaction! pool
                       (fn [tx]
                         (short-codes/run-again-on-code-clash!
                          tx "batch__short_code"
                          (fn [tx]
                            ;; the first run meets the taken code, as two random codes would
                            (if (= 1 (swap! calls inc))
                              (run-sql! tx "INSERT INTO batches (batch__shop, batch__short_code) VALUES ('b', 'BTAKEN22')")
                              (run-sql! tx "INSERT INTO batches (batch__shop) VALUES ('b')"))))
                         (run-sql! tx "INSERT INTO batches (batch__shop) VALUES ('c')")))
      (is (= 2 @calls))
      (is (= #{"a" "b" "c"} (set (map :batch/shop (find-rows pool "SELECT batch__shop FROM batches")))))
      (is (= 3 (count (set (codes-of pool "batches" "batch"))))))))

(deftest a-second-clash-or-another-error-is-thrown-as-it-came
  (when-postgres "postgres-test"
    (run-sql! pool "INSERT INTO batches (batch__shop, batch__short_code) VALUES ('a', 'BTAKEN22')")
    (testing "a second clash"
      (let [calls (atom 0)]
        (is (= "23505" (sql-state #(in-transaction! pool
                                                    (fn [tx]
                                                      (short-codes/run-again-on-code-clash!
                                                       tx "batch__short_code"
                                                       (fn [tx]
                                                         (swap! calls inc)
                                                         (run-sql! tx "INSERT INTO batches (batch__shop, batch__short_code) VALUES ('b', 'BTAKEN22')"))))))))
        (is (= 2 @calls))))
    (testing "another error runs once"
      (let [calls (atom 0)]
        (is (= "23502" (sql-state #(in-transaction! pool
                                                    (fn [tx]
                                                      (short-codes/run-again-on-code-clash!
                                                       tx "batch__short_code"
                                                       (fn [tx]
                                                         (swap! calls inc)
                                                         (run-sql! tx "INSERT INTO batches (batch__shop) VALUES (NULL)"))))))))
        (is (= 1 @calls))))
    (testing "a clash in another column is not a code clash"
      (run-sql! pool "CREATE UNIQUE INDEX batches_quantity_once ON batches (batch__quantity)")
      (let [calls (atom 0)]
        (is (= "23505" (sql-state #(in-transaction! pool
                                                    (fn [tx]
                                                      (short-codes/run-again-on-code-clash!
                                                       tx "batch__short_code"
                                                       (fn [tx]
                                                         (swap! calls inc)
                                                         (run-sql! tx "INSERT INTO batches (batch__shop, batch__quantity) VALUES ('b', 1)"))))))))
        (is (= 1 @calls))))
    (is (= ["a"] (map :batch/shop (find-rows pool "SELECT batch__shop FROM batches"))) "nothing else went in")))

(deftest the-retry-runs-only-inside-a-transaction
  (when-postgres "postgres-test"
    (is (= :groundwork-short-codes/not-in-a-transaction
           (try (short-codes/run-again-on-code-clash! pool "batch__short_code" (fn [_] nil)) nil
                (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))
