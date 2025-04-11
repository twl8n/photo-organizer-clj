
-- sqlite3 
-- GIS?

-- This is an individual photo record. 
create table "photo"
    (
	photo_pk integer primary key autoincrement,
	vroll_fk integer,
	vroll_order double,
	pathfile_name text, -- relative to the collection root path
	description text,
        photo_date text -- ISO 8601 date/time '2025-05-29 14:16:00'
        place_fk integer,
        FOREIGN KEY(vroll_fk) REFERENCES vroll(vroll_pk),
        FOREIGN KEY(place_fk) REFERENCES place(place_pk)
        );

-- select person.name
-- from person, photo_person
-- where photo_fk={photo_pk}
-- and person_fk=person_pk;

-- Do we need ordering, left to right, front to back, in the photo? (yes)
-- Or x,y coords in the photo? (Maybe)

-- linking table, one photo to many person
create table "photo_person"
    (
        photo_fk integer, -- one photo to many photo_person 
        person_fk integer
        FOREIGN KEY(photo_fk) REFERENCES photo(photo_pk),
        FOREIGN KEY(person_fk) REFERENCES person(person_pk)
);

-- a person found in at least one photo.
create table "person"
    (
	person_pk integer primary key autoincrement,
        name text
);

-- place where photo was taken
create table "place"
    (
	place_pk integer primary key autoincrement,
        name text,
        street1 text,
        street2 text,
        city text,
        state text, 
        zip_code text,
        longitude text,
        latitude text
);

-- virtual film roll, might be a real, physical film roll.
-- rename to "roll" and add "virtual" boolean?
create table "vroll"
    (
        vroll_pk integer primary key autoincrement,
        vroll_name text, -- short desc
        vroll_desc text, -- long desc
        earliest_date text, 
        latest_date text
        );



