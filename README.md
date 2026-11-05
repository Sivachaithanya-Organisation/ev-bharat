# ev-bharat
ublic EV charging station across the india in highways or in the city. Application tech stack is java21, maven and tomcat 9.123. And show case the EV culture in website and what are present top models of EV in india of 4 wheeler and 2 wheeler and how the govt is supporting the Ev technology in india

EV Bharat as a Java 21 + Maven WAR for Tomcat 9, but I haven’t compiled or run it. This environment can’t reach Maven Central and has no javac. I only checked that the JSON data files parse and the JavaScript has no syntax errors. Run mvn clean package first; it also runs 3 JUnit tests on the station search.

## What you get

## Find chargers: a Leaflet/OpenStreetMap map with 48 stations along major highways (Delhi–Jaipur, Mumbai–Pune, Bengaluru–Mysuru, Hyderabad–Vijayawada and others) and in big cities. You can filter by highway or city, state, connector, and charging speed, or tap “near me” for nearest-first results.

## Electric cars and two-wheelers: 15 cars and 11 two-wheelers with claimed range and battery size. Each page has an October 2026 sales chart: Tata, Mahindra and MG lead cars, and TVS, Bajaj and Ather lead two-wheelers.

## Government support: PM E-DRIVE (extended to March 2028), charging guidelines, 5% GST, the car-manufacturing import-duty scheme, battery and auto production incentives, state policies, and PM e-Bus Sewa.

## EV culture: plug types explained, a highway trip checklist, and a timeline of milestones.

## API: a small servlet serves the data at /api/*. To update content, edit the JSON files in src/main/resources/data.

Deploy: copy target/ev-bharat.war into Tomcat’s webapps, then open http://localhost:8080/ev-bharat/.
