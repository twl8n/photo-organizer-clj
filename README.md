# photo-organizer-clj
Organize a photo collection. Identify, group, and edit: location, person(s), film roll.

Web application, multiuser. 

Eventually, bidirectional copy relevant data into/from image metadata for each file.

- virtual rolls (same as a virtual album?), has many-to-many linking table with photo
- table of persons "person" singular, has many-to-many linking table with photo.
- table of places "place", only one place per photo, 

Linking table photo_person allows multiple person per photo, person in multiple photos. 

Photo has 3 dates. If photo has a known date, all three can be identical, otherwise fill in photo_min_date,
photo_max_date. What value in photo_date? Average of min and max? If all date searches are based on a range,
why have photo_date?

The same photo can be in multiiple virtual rolls, so virtual roll needs an intermediate table vroll_order with
photo order.

Need test/dev mode that works on a small photo directory. 

Can Clojure read command line args? Use command line arg to switch to dev config.

`clj -X cmgr.core/-main`

todo:

- 2026-03-10 Fix that in-ns nonsense in machine. It wasn't necessary here, and is probably a red herring over there too.

- update deps to recent versions

x Add an "open" call to open the browser automatically.

- Physically rotate files that have a virtual rotation. Not all software works properly with rotation meta
data.

! write a better quick description of the V5 state table in state.clj

- allow/require naked base url. Remove/change the regex match #".*/porg[/]*". Either redirect '/porg` or make
  it like it never existed.

quickstart:

```
cd ~/
sqlite3 porg.db < src/photo-organizer-clj/schema_sqlite.sql
```

Run the app:

`clj -X porg.core/-main`

The program runs, and your default web browser will open with:

`http://localhost:8081/porg`

#### dev quickstart

```
cd src/photo-organizer-clj
clj -M:nREPL -m nrepl.cmdline
```

In emacs:
cider-connect accepting defaults

Open a code buffer and:
cider-load-buffer C-c C-k
cider-repl-set-ns C-c M-n n

Split window and open the cider buffer, work as usual.

cider keystrokes to remember
C-c M-n         cider-ns-map
C-c M-n n       cider-repl-set-ns
C-c C-k         cider-load-buffer
C-c C-e         cider-eval-last-sexp
C-c M-i         cider-inspect
C-c M-z         cider-load-buffer-and-switch-to-repl-buffer



#### config

```
cd ~/
sqlite3 porg.db
```

`insert into config values ('pic_root_path', '/users/zeus/photos');`
