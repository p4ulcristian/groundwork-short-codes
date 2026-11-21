# groundwork-short-codes

A row's short code, set once on insert: `BK7Q2MX4`, its kind's letter and
random characters from 31 (A–Z without I, L, O; 2–9), in a column of the
row itself. Unique, never changed, deleted and restored with its row. For
addresses people scan or type: `https://example.com/c/BK7Q2MX4`.

```clojure
io.github.p4ulcristian/groundwork-short-codes {:git/tag "v0.1.2" :git/sha "41ecd9f"}
```

## What an app declares

The two functions, in its declared `:functions`, and per table a column
and the trigger that fills it (groundwork-postgres table maps):

```clojure
(require '[groundwork-short-codes.api :as short-codes])

{:functions (into app-functions short-codes/function-files)}

{:table    :batches
 :columns  [[:batch/id :uuid {:primary-key? true}]
            [:batch/short-code :string {:unique? true}]
            …]
 :triggers [(short-codes/make-trigger {:letter "B" :column-name "batch__short_code" :random-length 7})]
 …}
```

A row inserted without a code gets one; a row that comes with one keeps it.
A table with rows adds the column `{:optional? true}` first, fills the old
rows with a change file
(`UPDATE batches SET batch__short_code = public.groundwork_short_codes_make_code('B', 7)`),
and makes it required in a later deploy.

## The api

| function | does |
|---|---|
| `function-files` | the two `.sql` files an app puts in `:functions` |
| `make-trigger` | the BEFORE INSERT trigger a table map puts in `:triggers`, every value written out |
| `read-code` | a typed or scanned code as stored: capitals, spaces and dashes dropped; nil if it can't be a code |
| `find-code-in-address` | the code of `https://host/c/BK7Q2MX4` (any case), nil for any other address |
| `find-letter` | a code's kind letter |
| `write-code-in-groups` | a code for people: `BK7Q-2MX4` |
| `run-again-on-code-clash!` | server only: runs an insert under a savepoint and once more when two random codes met |
| `is-code-clash?` | server only: whether an exception is a duplicate in that code column |

Two random codes meet rarely, but not never: at 7 characters, 200,000 rows
in one table meet about once. The unique index refuses the second; wrap
each insert of rows with codes in `run-again-on-code-clash!`.

## Tests

```bash
clojure -X:test                                   # the api
POSTGRES_TEST_PORT=5499 POSTGRES_TEST_USER=$USER POSTGRES_TEST_PASSWORD=… clojure -X:test   # and Postgres
```

The Postgres tests empty the database's public folder and make the role
`short_codes_app`: give them a Postgres of their own (`POSTGRES_TEST_DATABASE`,
`postgres` when unset).

MIT license.
