(ns groundwork-short-codes.api-test
  "The api without a database: reading a typed or scanned code, finding it
   in an address, writing it for people, and the trigger a table declares."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [groundwork-short-codes.api :as short-codes]))

(deftest the-alphabet-has-31-characters-and-no-look-alikes
  (is (= 31 (count short-codes/alphabet) (count (set short-codes/alphabet))))
  (is (not-any? (set short-codes/alphabet) "01ILO")))

(deftest a-code-is-read-the-way-it-is-stored
  (testing "capitals, spaces and dashes dropped"
    (is (= "BK7Q2MX4" (short-codes/read-code "BK7Q2MX4")))
    (is (= "BK7Q2MX4" (short-codes/read-code "bk7q2mx4")))
    (is (= "BK7Q2MX4" (short-codes/read-code " BK7Q-2MX4 ")))
    (is (= "BK7Q2MX4" (short-codes/read-code "B K7Q 2MX4"))))
  (testing "the first character is any capital letter, the kind's"
    (is (= "IK7Q2MX4" (short-codes/read-code "IK7Q2MX4")))
    (is (nil? (short-codes/read-code "7K7Q2MX4"))))
  (testing "after it only the alphabet"
    (doseq [bad ["BK7Q2MX0" "BK7Q2MX1" "BK7Q2MXI" "BK7Q2MXL" "BK7Q2MXO" "BK7Q2MX_" "BK7Q2MXÉ"]]
      (is (nil? (short-codes/read-code bad)) bad)))
  (testing "a letter and 4 to 16 characters"
    (is (nil? (short-codes/read-code "BK7Q")))
    (is (= "BK7Q2" (short-codes/read-code "BK7Q2")))
    (is (= 17 (count (short-codes/read-code "BAAAABBBBCCCCDDDD"))))
    (is (nil? (short-codes/read-code "BAAAABBBBCCCCDDDDE"))))
  (testing "not text"
    (is (nil? (short-codes/read-code nil)))
    (is (nil? (short-codes/read-code 42)))
    (is (nil? (short-codes/read-code "")))))

(deftest a-code-is-found-only-in-a-short-address
  (is (= "BK7Q2MX4" (short-codes/find-code-in-address "https://example.com/c/BK7Q2MX4")))
  (is (= "BK7Q2MX4" (short-codes/find-code-in-address "HTTPS://EXAMPLE.COM/C/BK7Q2MX4")))
  (is (= "BK7Q2MX4" (short-codes/find-code-in-address "http://localhost:4100/c/bk7q-2mx4/")))
  (is (= "BK7Q2MX4" (short-codes/find-code-in-address "/c/BK7Q2MX4")))
  (is (= "BK7Q2MX4" (short-codes/find-code-in-address "  /C/BK7Q2MX4\n")))
  (doseq [other ["https://host/flex/ws/1/batches/2?batch=3"
                 "https://host/c/BK7Q2MX4/more"
                 "https://host/x/c/BK7Q2MX4"
                 "https://host/c/BK7Q2MX4?x=1"
                 "https://host/c/"
                 "BK7Q2MX4"
                 nil]]
    (is (nil? (short-codes/find-code-in-address other)) (pr-str other))))

(deftest a-code-is-written-for-people-in-groups-of-four
  (is (= "BK7Q-2MX4" (short-codes/write-code-in-groups "BK7Q2MX4")))
  (is (= "BK7Q-2MX4-P" (short-codes/write-code-in-groups "BK7Q2MX4P")))
  (is (= "BK7Q" (short-codes/write-code-in-groups "BK7Q")))
  (is (= "BK7Q2MX4" (short-codes/read-code (short-codes/write-code-in-groups "BK7Q2MX4"))) "and read back"))

(deftest a-codes-letter-is-its-first
  (is (= "B" (short-codes/find-letter "BK7Q2MX4"))))

(deftest a-table-declares-the-trigger-with-every-value
  (is (= {:function  "groundwork_short_codes_fill_code"
          :arguments ["B" "batch__short_code" "7"]
          :timing    :before
          :events    #{:insert}
          :update-of nil
          :for-each  :row
          :when      nil}
         (short-codes/make-trigger {:letter "B" :column-name "batch__short_code" :random-length 7})))
  (doseq [bad [{:letter "b" :column-name "batch__short_code" :random-length 7}
               {:letter "BB" :column-name "batch__short_code" :random-length 7}
               {:letter "B" :column-name "Batch Code" :random-length 7}
               {:letter "B" :column-name "batch__short_code" :random-length 3}
               {:letter "B" :column-name "batch__short_code" :random-length 17}
               {:letter "B" :column-name "batch__short_code" :random-length "7"}
               {:letter "B" :column-name "batch__short_code"}
               {:letter "B" :column-name "batch__short_code" :random-length 7 :extra true}]]
    (is (= :groundwork-short-codes/bad-trigger
           (try (short-codes/make-trigger bad) nil
                (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
        (pr-str bad))))

(deftest the-function-files-are-on-the-classpath
  (doseq [file short-codes/function-files]
    (is (some? (io/resource file)) file)))
