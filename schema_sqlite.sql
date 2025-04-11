
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

-- linking table, one photo to many person

-- select person.name
-- from person, photo_person
-- where photo_fk={photo_pk}
-- and person_fk=person_pk;

create table "photo_person"
    (
        photo_fk integer, -- one photo to many photo_person 
        person_fk integer
        FOREIGN KEY(photo_fk) REFERENCES photo(photo_pk),
        FOREIGN KEY(person_fk) REFERENCES person(person_pk)
);

create table "person"
    (
	person_pk integer primary key autoincrement,
        name text
);

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

-- virtual film roll

create table "vroll"
    (
        vroll_pk integer primary key autoincrement,
        vroll_name text, -- short desc
        vroll_desc text, -- long desc
        earliest_date text, 
        latest_date text
        );



