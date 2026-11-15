# ev-bharat
ublic EV charging station across the india in highways or in the city. Application tech stack is java21, maven and tomcat 9.123. And show case the EV culture in website and what are present top models of EV in india of 4 wheeler and 2 wheeler and how the govt is supporting the Ev technology in india

EV Bharat as a Java 21 + Maven WAR for Tomcat 9, but I haven’t compiled or run it. This environment can’t reach Maven Central and has no javac. I only checked that the JSON data files parse and the JavaScript has no syntax errors. Run mvn clean package first; it also runs 3 JUnit tests on the station search.

## What you get

## Find chargers: a Leaflet/OpenStreetMap map with 1,000 Indian charging stations imported from Open Charge Map. Pick a From and Destination to see only the stations along that route. You can filter by highway or city, state, connector, and charging speed, or tap “near me” for nearest-first results.

## Electric cars and two-wheelers: 15 cars and 11 two-wheelers with claimed range and battery size. Each page has an October 2026 sales chart: Tata, Mahindra and MG lead cars, and TVS, Bajaj and Ather lead two-wheelers.

## Government support: PM E-DRIVE (extended to March 2028), charging guidelines, 5% GST, the car-manufacturing import-duty scheme, battery and auto production incentives, state policies, and PM e-Bus Sewa.

## EV culture: plug types explained, a highway trip checklist, and a timeline of milestones.

## API: a small servlet serves the data at /api/*. Station data currently comes from `src/main/resources/data/stations.json` via `/api/stations` (not from Google Maps).

For larger live coverage, connect an official/provider API feed (for example Open Charge Map, CPO APIs, or a licensed Google Places integration with your own API key) and refresh `stations.json` or ingest it server-side.

## Accounts and rental chargers: users sign up / log in (BCrypt-hashed passwords, session cookie) and list their own charger with price and contact phone. Listings are stored in PostgreSQL and show as blue markers on the map. The tables are created automatically on first use.

Database setup (any PostgreSQL 13+):

```
docker run -d --name evbharat-db -e POSTGRES_DB=evbharat -e POSTGRES_USER=evbharat -e POSTGRES_PASSWORD=change-me -p 5432:5432 postgres:16
```

Give Tomcat these environment variables: `DB_URL` (default `jdbc:postgresql://localhost:5432/evbharat`), `DB_USER`, `DB_PASSWORD`. If the database is down, the rest of the site keeps working and the rent feature shows an error. Serve the site over HTTPS in production so login cookies are protected.

Deploy: copy target/ev-bharat.war into Tomcat’s webapps, then open http://localhost:8080/ev-bharat/.

## Database
ubuntu@ip-172-31-44-92:~/ev-bharat$ docker exec -it evbharat-db psql -U evbharat -d evbharat
psql (16.16 (Debian 16.16-1.pgdg13+1))
Type "help" for help.

evbharat=# /d
evbharat-# /dt
evbharat-# 
evbharat-# \dt
             List of relations
 Schema |     Name      | Type  |  Owner   
--------+---------------+-------+----------
 public | charge_points | table | evbharat
 public | users         | table | evbharat
(2 rows)

evbharat-# \q
ubuntu@ip-172-31-44-92:~/ev-bharat$ 
ubuntu@ip-172-31-44-92:~/ev-bharat$ docker exec evbharat-db psql -U evbharat -d evbharat -c "SELECT id, name, email, created_at, left(password_hash,12) AS hash_start FROM users;"
 id | name |                email                |          created_at           |  hash_start  
----+------+-------------------------------------+-------------------------------+--------------
  1 | siva | kanuparthisiva.chaithanya@gmail.com | 2026-11-14 21:17:54.290942+00 | $2a$11$4KS3Y
(1 row)

ubuntu@ip-172-31-44-92:~/ev-bharat$ 
ubuntu@ip-172-31-44-92:~/ev-bharat$ docker exec evbharat-db psql -U evbharat -d evbharat -c "SELECT id, owner_id, title, city, state, connector, power_kw, price_per_kwh, phone, active FROM charge_points;"
 id | owner_id |         title         |   city    |   state   | connector | power_kw | price_per_kwh |     phone     | active 
----+----------+-----------------------+-----------+-----------+-----------+----------+---------------+---------------+--------
  1 |        1 | Home Charger, Miyapur | Hyderabad | Telangana | CCS2      |        4 |         10.00 | +917995351635 | t
(1 row)

ubuntu@ip-172-31-44-92:~/ev-bharat$ 
ubuntu@ip-172-31-44-92:~/ev-bharat$ docker exec evbharat-db psql -U evbharat -d evbharat -c "\d users" -c "\d charge_points"
                                        Table "public.users"
    Column     |           Type           | Collation | Nullable |              Default              
---------------+--------------------------+-----------+----------+-----------------------------------
 id            | bigint                   |           | not null | nextval('users_id_seq'::regclass)
 email         | text                     |           | not null | 
 name          | text                     |           | not null | 
 password_hash | text                     |           | not null | 
 created_at    | timestamp with time zone |           | not null | now()
Indexes:
    "users_pkey" PRIMARY KEY, btree (id)
    "users_email_key" UNIQUE CONSTRAINT, btree (email)
Referenced by:
    TABLE "charge_points" CONSTRAINT "charge_points_owner_id_fkey" FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE

                                        Table "public.charge_points"
    Column     |           Type           | Collation | Nullable |                  Default                  
---------------+--------------------------+-----------+----------+-------------------------------------------
 id            | bigint                   |           | not null | nextval('charge_points_id_seq'::regclass)
 owner_id      | bigint                   |           | not null | 
 title         | text                     |           | not null | 
 city          | text                     |           | not null | 
 state         | text                     |           | not null | 
 lat           | double precision         |           | not null | 
 lng           | double precision         |           | not null | 
 connector     | text                     |           | not null | 
 power_kw      | integer                  |           | not null | 
 price_per_kwh | numeric(8,2)             |           | not null | 
 phone         | text                     |           | not null | 
 notes         | text                     |           | not null | ''::text
 active        | boolean                  |           | not null | true
 created_at    | timestamp with time zone |           | not null | now()
Indexes:
    "charge_points_pkey" PRIMARY KEY, btree (id)
    "charge_points_owner_idx" btree (owner_id)
Foreign-key constraints:
    "charge_points_owner_id_fkey" FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE

ubuntu@ip-172-31-44-92:~/ev-bharat$ 
