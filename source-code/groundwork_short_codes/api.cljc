(ns groundwork-short-codes.api
  "A row's short code, set once on insert: its kind's letter and random
   characters from 31 (A-Z without I, L, O; 2-9), in a column of the row
   itself, unique, never changed. Postgres makes it (the two .sql files
   beside this one); this namespace gives an app what it declares and
   reads a code a person typed or a camera scanned. The same in both
   languages."
  (:require
   [clojure.string :as str]
   #?(:clj [groundwork-postgres.api :as postgres])))

(def alphabet
  "The 31 characters after the letter: no 0/O, 1/I/L to mix up."
  "ABCDEFGHJKMNPQRSTUVWXYZ23456789")

(def function-files
  "The two functions an app puts in its declared :functions, as files on
   the classpath: making a code, and the trigger that fills one on insert."
  ["groundwork_short_codes/make_short_code.sql"
   "groundwork_short_codes/fill_short_code.sql"])

(def ^:private letters (set "ABCDEFGHIJKLMNOPQRSTUVWXYZ"))

(defn make-trigger
  "The BEFORE INSERT trigger a table map puts in its :triggers, every value
   written out: :letter, the kind's one capital letter; :column-name, the
   column as Postgres names it (\"batch__short_code\"); :random-length, the
   random characters after the letter. Throws on a value that doesn't fit."
  [{:keys [letter column-name random-length] :as settings}]
  (when-not (and (string? letter) (= 1 (count letter)) (contains? letters (first letter))
                 (string? column-name) (re-matches #"[a-z_][a-z0-9_]*" column-name)
                 (int? random-length) (<= 4 random-length 16)
                 (= #{:letter :column-name :random-length} (set (keys settings))))
    (throw (ex-info (str "A short code's trigger takes {:letter \"B\" :column-name \"batch__short_code\" :random-length 7}, not "
                         (pr-str settings))
                    {:type :groundwork-short-codes/bad-trigger :settings settings})))
  {:function  "groundwork_short_codes_fill_code"
   :arguments [letter column-name (str random-length)]
   :timing    :before
   :events    #{:insert}
   :update-of nil
   :for-each  :row
   :when      nil})

(defn read-code
  "A code as typed or scanned, made the way it is stored: capitals, its
   spaces and dashes dropped. nil when what is left can't be a code: a
   letter, then 4 to 16 characters of the alphabet."
  [text]
  (when (string? text)
    (let [code (-> text str/upper-case (str/replace #"[\s-]" ""))]
      (when (and (<= 5 (count code) 17)
                 (contains? letters (first code))
                 (every? (set alphabet) (rest code)))
        code))))

(defn find-code-in-address
  "The code of a short address, https://host/c/BK7Q2MX4 (any case, the
   path's /c/ as well as /C/), made as read-code makes it; nil for any other
   address."
  [text]
  (when (string? text)
    (when-let [[_ code] (re-find #"^(?:[a-zA-Z][a-zA-Z0-9+.-]*://[^/]+)?/[cC]/([A-Za-z0-9-]+)/?$" (str/trim text))]
      (read-code code))))

(defn find-letter
  "A code's kind letter: \"B\" of BK7Q2MX4."
  [code]
  (subs code 0 1))

(defn write-code-in-groups
  "A code as people read it: groups of four from the start, BK7Q-2MX4."
  [code]
  (str/join "-" (map str/join (partition-all 4 code))))

;; -----------------------------------------------------------------------------
;; A code clash, on the server

#?(:clj
   (do
     (def ^:private set-savepoint (postgres/make-query-from-sql-file "groundwork_short_codes/set_savepoint.sql"))
     (def ^:private release-savepoint (postgres/make-query-from-sql-file "groundwork_short_codes/release_savepoint.sql"))
     (def ^:private undo-to-savepoint (postgres/make-query-from-sql-file "groundwork_short_codes/undo_to_savepoint.sql"))

     (defn is-code-clash?
       "Whether this exception, or one it wraps, is Postgres refusing a
        duplicate in the code column column-name (\"batch__short_code\")."
       [exception column-name]
       (boolean
        (some #(and (instance? java.sql.SQLException %)
                    (= "23505" (.getSQLState ^java.sql.SQLException %))
                    (str/includes? (str (.getMessage ^Throwable %)) (str "Key (" column-name ")=")))
              (take-while some? (iterate #(.getCause ^Throwable %) exception)))))

     (defn- run-under-savepoint! [transaction f]
       (postgres/run-statement! transaction set-savepoint {})
       (let [result (f transaction)]
         (postgres/run-statement! transaction release-savepoint {})
         result))

     (defn run-again-on-code-clash!
       "Runs (f transaction), the statements that insert rows with codes in
        column-name, under a savepoint; when Postgres refuses a code that
        is taken (two random codes met, about 1 in 27.5 billion per row at 7
        characters), goes back to the savepoint and runs f once more, with
        new codes. The transaction goes on either way. Returns what f
        returns; any other error, or a second clash, is thrown as it came.
        Inside a transaction only (groundwork-postgres.api/run-in-transaction!)."
       [transaction column-name f]
       (when-not (postgres/is-transaction? transaction)
         (throw (ex-info "run-again-on-code-clash! runs inside a transaction, for its savepoint"
                         {:type :groundwork-short-codes/not-in-a-transaction})))
       (try
         (run-under-savepoint! transaction f)
         (catch Exception e
           (if-not (is-code-clash? e column-name)
             (throw e)
             (do (postgres/run-statement! transaction undo-to-savepoint {})
                 (postgres/run-statement! transaction release-savepoint {})
                 (run-under-savepoint! transaction f))))))))
