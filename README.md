# photo-organizer-clj
Organize a photo collection. Identify, group, and edit: location, person(s), film roll.


Web application, so could be multiuser. 

Eventually, copy relevant data into image metadata for each file.

todo:

- update deps to recent versions

- Add an "open" call to open the browser automatically.

- Physically rotate files that have a virtual rotation. Not all software works properly with rotation meta
data.

- write a better quick description of the V5 state table in state.clj

quickstart:

```
cd ~/
sqlite3 porg.db < src/photo-organizer-clj/schema_sqlite.sql
```

Really? "-M -m". Seems like I'm confused.

`clojure -M -m porg.core`
