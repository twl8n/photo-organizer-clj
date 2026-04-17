-- See the readme for an quick explanation of HugSQL headers.
-- Examples from the hugsql github repo:
-- https://github.com/layerware/hugsql/blob/master/examples/princess-bride/src/princess_bride/db/sql/characters.sql

-- :name sql-config :? :*
-- :doc Read config values from sqlite
select name,value from config;

-- :name sql-insert-filename :! :n
-- :doc insert a full path file name of a single photo, but not if it already exists
INSERT OR IGNORE INTO photo (pathfile_name) 
VALUES (:pathfile_name);

-- :name sql-records-found :? :1
-- :doc Return the number of photo records
select count(*) as recordsfound from photo;

-- :name sql-firstnon :? :1
-- :doc return full photo record with the shortest description, which might be null.
select * from photo
order by length(description) asc limit 1;

-- :name sql-select-photo :? :1
-- :doc return full photo path file name of a record with no annotations
SELECT *
FROM photo 
left JOIN place ON photo.place_fk = place.place_pk
where photo_pk = :photo_pk;

-- :name sql-select-photo-person :? :*
-- :doc Only return person_fk since there is a single photo_fk. I think.
select person_fk,person.name from photo_person,person
where photo_fk = :photo_fk
    and person_pk=person_fk
order by person.name asc;

-- :name sql-next-photo :? :1
-- :doc return next photo based on photo_pk, wrap around when asked for > max photo_pk.
select * from
    (select * from
        (select * from photo where photo_pk > :photo_pk order by photo_pk limit 1)
    union 
    select * from photo where photo_pk = (select min(photo_pk) from photo))
order by photo_pk desc limit 1;

-- :name sql-previous-photo :? :1
-- :doc return previous photo based on photo_pk, wrap around when asked for < min photo_pk.
select * from (
    select * from
        (select * from photo where photo_pk < :photo_pk order by photo_pk desc limit 1)
    union 
    select * from photo where photo_pk = (select max(photo_pk) from photo)
        ) order by photo_pk asc limit 1;

-- :name sql-select-all-person :? :*
-- :doc select all fields from the person table
select * from person order by name asc;

-- :name sql-related-person :? :*
select * from person where
    related_pk = :related_pk;

-- :name sql-insert-person :! :n
-- :doc insert a new person
insert into person
    (name, pdesc, birth_date, death_date, canonical_photo_fk, related_pk, is_primary)
    values
    (:name, :pdesc, :birth_date, :death_date, :canonical_photo_fk, :related_pk, :is_primary);

-- :name sql-update-person :! :n
-- :doc update person
update person set
    name=:name,
    pdesc=:pdesc,
    birth_date=:birth_date,
    death_date=:death_date,
    canonical_photo_fk=:canonical_photo_fk,
    related_pk=:related_pk,
    is_primary=:is_primary
where person_pk=:person_pk;

-- :name sql-select-person :? :1
select * from person where person_pk=:person_pk;

-- :name sql-save-photo :! :n
update photo set description=:description,
    photo_date=:photo_date,
    photo_min_date=:photo_min_date,
    photo_max_date=:photo_max_date,
    place_fk=:place_fk
where photo_pk=:photo_pk;

-- :name sql-delete-photo-person :! :n
delete from photo_person where photo_fk = :photo_fk;

-- :name sql-insert-photo-person :! :n
insert into photo_person (photo_fk, person_fk) values (:photo_fk,:person_fk);

-- :name sql-select-all-place :? :*
select * from place order by place_name;

-- :name sql-update-pplace :! :n
update photo set place_fk = :place_fk where photo_pk = :photo_pk;

-- :name sql-select-place :? :1
select * from place where place_pk = :place_pk;

-- :name sql-insert-place :! :n
insert into place (place_name, place_desc, street1, street2, city, state, zip_code, longitude, latitude)
values (:place_name, :place_desc, :street1, :street2, :city, :state, :zip_code, :longitude, :latitude);

-- :name sql-update-place :! :n
update place
set place_name = :place_name,
    place_desc = :place_desc,
    street1 = :street1,
    street2 = :street2,
    city = :city,
    state = :state,
    zip_code = :zip_code,
    longitude = :longitude,
    latitude = :latitude
where place_pk = :place_pk;
