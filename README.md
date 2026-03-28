#### Photo Organizer CLJ
Manually annotate and organize a photo collection. Identify, group, and edit: location, person(s), dates/ranges, film roll, virtual albums.

Web application, multiuser. 

Eventually, bidirectional copy relevant data into/from image metadata for each file.

- virtual rolls (same as a virtual album?), has many-to-many linking table with photo
- table of persons "person" singular, has many-to-many linking table with photo.
- table of places "place", only one place per photo, 

Linking table photo_person allows multiple person per photo, person in multiple photos. 

Photo has 3 dates. If photo has a known date, all three can be identical, otherwise fill in photo_min_date,
photo_max_date. What value in photo_date? Average of min and max? If all date searches are based on a range,
why have photo_date?

The same photo can be in multiiple virtual rolls, so virtual roll has a linking table, vroll_order, with
photo order.

Need test/dev mode that works on a small photo directory. 

Can Clojure read command line args? (Yes.) Use command line arg to switch to dev config.

`clj -X porg.core/-main`

#### todo:

- + Edit person loses photo_pk and choose-this.

- 2026-03-22 Can't clear states during traverse because the traverse is already being called, and an internal copy of the
  "state". It would need to call functions to get state instead of using keywords. That would help, and then
  we could set/clear state dynamically.

- Is my Emacs missing cider-nrepl plugin?
  WARNING: CIDER requires cider-nrepl to be fully functional. Some features will not be available without it! (More information)

- What is this error? Occurs seconds after cider-jack-in, change ns to porg.core, compile porg.core, but haven't run anything.

```
java.lang.NullPointerException: Cannot invoke "jdk.javadoc.internal.doclets.formats.html.HtmlConfiguration.getOptions()" because "this.configuration" is null
```

See ~/java-error.tmp

- ? Is there any way that "back" works? Currently use s_save_choice

```
<input name="s_back" value="Back/Previous" type="submit">
<br>
[:s_back back-state :exit]
```

- x Add "jump to" on start page.
- Retain most recently viewed image. Maybe store most recent in db? rather than keeping web state.
- + Add ability to edit photo > person > new person > choose person > back to photo.
- Add user table (even without authentication) to keep user settings, history.
- Add ability to mark image as duplicate, and link to other copies of same image.

- Add ipv6

```
(defn get-client-ip [req]
  (if-let [ips (get-in req [:headers "x-forwarded-for"])]
    (-> ips (clojure.string/split #",") first)
    (:remote-addr req)))
;; This might allow ipv6:
;; (def app  (rfm/wrap-allow-ips site-handler {:allow-list ["::1" "2001:db8::/32"]}))
```
    
- x Add previous photo button
- x Disable populate-db. That really needs to be a manual operation run from a repl or command line, but not web.

- 2026-03-21 Did I try to compile the code before the nrepl was fully running? 

`java.lang.NullPointerException: Cannot invoke "jdk.javadoc.internal.doclets.formats.html.HtmlConfiguration.getOptions()" because "this.configuration" is null`

- 2026-03-21 Fixed (work around) by cider-load-buffer on core.clj. How to get cider to load all the .clj buffers? Or at least core.clj and state.clj?

```
(porg.core/-main)
Execution error (ClassNotFoundException) at java.net.URLClassLoader/findClass (URLClassLoader.java:445).
porg.core
java.lang.NullPointerException: Cannot invoke "jdk.javadoc.internal.doclets.formats.html.HtmlConfiguration.getOptions()" because "this.configuration" is null
```

- + 2026-03-20 Need sql field "name" to only be used once. Change place.name to place.place_name with alter
table and code fixes: ALTER TABLE place RENAME COLUMN name TO place_name;
- 2026-03-20 Change places where {{name}} needs to be {{place_name}}
- 2026-03-16 ;; Remove leading part of the full path, creating a "path" that is relavtive to the "image" symlink.
- 2026-03-16  The symlink is hard coded, created manually. Should be in config, created by this app.

`;; The directory path is hard coded here, and should be in config.`

- 2026-03-16 Create thumbnails and decide if they will reside in the db (yes) or on disk in a parallel directory tree

- 2026-03-16 Refactor state.clj for clarity and logical progression. 

- 2026-03-16 Condense and unify sql db connectors in state.clj

- 2026-03-10 Fix that in-ns nonsense in machine. It wasn't necessary here, and is probably a red herring over there too.

- update deps to recent versions

- x Add an "open" call to open the browser automatically.

- Physically rotate image files that have a virtual rotation. Not all software works properly with rotation meta
data.

- ! write a better quick description of the V5 state table in state.clj

- allow/require naked base url. Remove/change the regex match #".*/porg[/]*". Either redirect '/porg` or make
  it like it never existed.

#### Quickstart:

```
cd ~/
sqlite3 porg.db < src/photo-organizer-clj/schema_sqlite.sql
sqlite3 porg.db
insert into config values ("image-path-base", "/Volumes/external/family/");
```

#### Run the app:

`clj -X porg.core/-main`

The program runs, and your default web browser will open with:

`http://localhost:8081/porg`

#### dev quickstart

```
cd src/photo-organizer-clj
clj -M:nREPL -m nrepl.cmdline
```

#### NetPBM 

```
# This "rotate 90" is -90 aka 90 CCW
jpegtopnm -dumpexif IMG_0494.JPG 2>&1 > /dev/null | grep -i orient
jpegtopnm: Orientation  : Rotate 90

# jpegtran "-rotate 90" is +90 aka 90 CW
jpegtran -rotate 90 IMG_0494.JPG > tmp.jpg

# 0494 is rotated 6, and this conversion produces a non-rotated image:
jpegtopnm IMG_0494.JPG | pamscale -height=1936 - | pnmtojpeg - > IMG_R0494.JPG
```

`jpegtopnm -traceexif IMG_0494.JPG > tmp.pnm`

```
> jpegtopnm -dumpexif IMG_0494.JPG > tmp.pnm
jpegtopnm: WRITING PPM FILE
jpegtopnm: EXIF INFO:
jpegtopnm: Camera make  : Apple
jpegtopnm: Camera model : iPod touch
jpegtopnm: Date/Time    : 2023:04:01 13:20:29
jpegtopnm: Resolution   : 72.000000 x 72.000000
jpegtopnm: Orientation  : Rotate 90
jpegtopnm: Flash used   : No
jpegtopnm: Exposure time: 0.0000 s
jpegtopnm: Shutter speed: 1/128
jpegtopnm: Aperture     : f/2.0
jpegtopnm: ISO equiv.   : 5000
jpegtopnm: Exposure bias: 0.00
jpegtopnm: Metering Mode: spot
jpegtopnm: Exposure     : program (auto)
jpegtopnm: Focal length :  3.0mm
```

```
# redirect stderr to stdout, and stdout to /dev/null, new stdout will be piped:
jpegtopnm -dumpexif IMG_0494.JPG 2>&1 > /dev/null | perl -ne 'print $_ if m/.*Orientation.*(\d+)$/;'

# make a rotated jped of the right size to confirm size and rotation
jpegtopnm IMG_3065.JPG |pnmrotate 270 - | pamscale -xyfit 2592 1936 - | ppmtojpeg - > tmp.jpg
jpegtopnm IMG_3323.JPG | ppmtojpeg - > tmp.jpg

jpegtopnm < IMG_3323.JPG | pnmscale -xsize=%s | pnmtojpeg > %s

jpegtopnm IMG_3065.JPG | pamscale -height=1936 - | pnmpaste - 551 1 tmp.ppm > IMG_3065.ppm

# works, pixel x y are zero based apparently
jpegtopnm IMG_3065.JPG | pnmrotate -90 - | pamscale -xyfit 2592 1936 - | pnmpaste - 550 0 tmp.ppm | pnmtojpeg - > IMG_3065R.jpg
```

#### Development in Emacs

In the shell:
`clj -M:nREPL -m nrepl.cmdline`

Then in emacs:
`cider-connect` accepting defaults

Or
`cider-jack-in` which seems to launch the nrepl from inside emacs and connecto it.

Open a code buffer and:
cider-load-buffer C-c C-k
cider-repl-set-ns C-c M-n n

Split window and open the cider buffer, work as usual.

After starting nrepl, always do these in the clojure source file:
C-c M-n n       cider-repl-set-ns
C-c C-k         cider-load-buffer

cider keystrokes to remember
C-c M-n         cider-ns-map
C-c M-n n       cider-repl-set-ns
C-c C-k         cider-load-buffer
C-c C-e         cider-eval-last-sexp
C-c M-i         cider-inspect
C-c M-z         cider-load-buffer-and-switch-to-repl-buffer
M-x -r-c-bu RET cider-repl-clear-buffer
C-c C-z 	    switch between the REPL and a Clojure file
M-.             jump to the source
C-c C-d C-d     view the documentation 

#### Config

```
cd ~/
sqlite3 porg.db
```

`insert into config values ('pic_root_path', '/users/zeus/photos');`

find /Volumes/external/family/2023-08-29 -type f > ~/tmp.txt
file --mime-type -f ~/tmp.txt > ~/tmp2.txt


