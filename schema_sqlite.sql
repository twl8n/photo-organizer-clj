
-- sqlite3 

create table "config"
    (
        name text,
        value text
);

create table "user"
    (
        user_pk integer primary key autoincrement,
        user_name text,
        uuid text,
        photo_pk integer,
        client_ip_addr text
);
insert into config (name,value)
values ("test", 12345);

-- GIS?

-- This is an individual photo record.
-- photo_pk used a foreign key in vroll_order linking table
-- photo_pk used a foreign key in photo_person linking table
-- pathfile_name is relative to the ring web server's idea of image root path. See wrap-file  in defn make-app in core.clj.
create table "photo"
    (
	photo_pk integer primary key autoincrement,
	pathfile_name text, -- see comment above
	description text,
        photo_date text, -- ISO 8601 date/time '2025-05-29 14:16:00'
        photo_min_date text, -- iso 8601 date/time
        photo_max_date text, -- iso 8601 date/time
        place_fk integer,
        UNIQUE(pathfile_name),
        FOREIGN KEY(place_fk) REFERENCES place(place_pk)
        );

-- Do we need ordering, left to right, front to back, in the photo? (yes)
-- Or x,y coords in the photo? (Maybe)

-- linking table, one photo to many person
create table "photo_person"
    (
        photo_fk integer, -- one photo to many photo_person 
        person_fk integer,
        FOREIGN KEY(photo_fk) REFERENCES photo(photo_pk),
        FOREIGN KEY(person_fk) REFERENCES person(person_pk)
);

-- a person found in at least one photo.
-- Initially assumed a single canonical image. That assumption is probably wrong.
-- 
create table "person"
    (
	person_pk integer primary key autoincrement,
        name text,
        pdesc text, -- description for this person
        birth_date text,
        death_date text,
        canonical_photo_fk integer,
        related_pk integer,
        is_primary integer -- 0 or 1
        );

-- place where photo was taken
create table "place"
    (
	place_pk integer primary key autoincrement,
        place_name text,
        street1 text,
        street2 text,
        city text,
        state text, 
        zip_code text,
        longitude text,
        latitude text
        );

-- Order of photo within a virtual roll.
-- linking table between photo and vroll.
create table "vroll_order"
    (
        photo_fk integer,
	vroll_fk integer,
	vroll_order double,
        FOREIGN KEY(vroll_fk) REFERENCES vroll(vroll_pk),
        FOREIGN KEY(photo_fk) REFERENCES photo(photo_pk)
        );

-- virtual film roll, might be a real, physical film roll, virtual roll, or virtual album.
-- rename to "roll" and add "virtual" boolean? (No, because also album. Maybe "album" boolean.)
create table "vroll"
    (
        vroll_pk integer primary key autoincrement,
        vroll_name text, -- short desc
        vroll_desc text, -- long desc
        earliest_date text, 
        latest_date text
        );



