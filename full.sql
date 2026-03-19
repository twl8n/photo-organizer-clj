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
-- :doc return full photo path file name of a record with no annotations
select photo_pk, pathfile_name from photo
where description is null order by photo_pk limit 1;

-- :name sql-photo-select :? :1
-- :doc return full photo path file name of a record with no annotations
select * from photo
where photo_pk = :photo_pk;

-- :name sql-next-photo-pk :? :1
-- :doc input a photo_pk and return the next photo_pk
select photo_pk from photo
where photo_pk > :photo_pk order by photo_pk limit 1;

-- :name sql-select-all-person :? :*
-- :doc select all fields from the person table
select * from person order by name desc;

-- :name sql-insert-person :! :n
-- :doc insert a new person
insert into person (name) values (:name);

-- :name sql-update-person :! :n
-- :doc update person
update person set name=:name where person_pk=:person_pk;

-- :name sql-select-person :? :1
select * from person where person_pk=:person_pk;

-- -----------------------------

-- https://sqlite.org/lang_conflict.html
-- create table uses "on conflict" but insert, update, upsert use "... or ignore"
-- ON CONFLICT(pathfile_name) DO NOTHING;

-- Not used, but alternative method to prevent inserting existing records.
-- INSERT INTO memos(id,text) 
-- SELECT 5, 'text to insert' 
-- WHERE NOT EXISTS(SELECT 1 FROM memos WHERE id = 5 AND text = 'text to insert');

-- Not used, also insert but not duplicate.
-- INSERT OR IGNORE INTO table1 (email) VALUES ('test@domain.com');

-- (ex (characters/insert-characters db {:characters [["Vizzini" "intelligence"]
--                                                      ["Fezzik" "strength"]
--                                                      ["Inigo Montoya" "swordmanship"]]}))
-- -- :name insert-characters :! :n
-- -- :doc Insert multiple characters with :tuple* parameter type
-- insert into characters (name, specialty)
-- values :tuple*:characters

-- (ex (characters/insert-character db {:name "Buttercup" :specialty "beauty"}))
-- -- A :result value of :n below will return affected row count:
-- -- :name insert-character :! :n
-- -- :doc Insert a single character
-- insert into characters (name, specialty)
-- values (:name, :specialty)
