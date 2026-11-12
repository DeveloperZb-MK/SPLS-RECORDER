# SPLS Recorder

A small Android app that does one thing: it records how the three location providers
(Network, GPS and Fused) behave on your phone, place by place, and lets you import and
export those records as CSV files.

It has no mock location, no Xposed or LSPosed part and no learned-behaviour deployment.
The map is key-free (MapLibre with OpenStreetMap, Esri and OpenTopoMap tiles).

## What it does

* Records Network, GPS and Fused fixes in the background (foreground service with a notification).
* Keeps one record (one CSV file) per SPLS location. A location can be "here and now", an
  area (circle with a radius) or a place you pick on the map.
* Shows every area on the map with a brain marker, its serial number and its radius circle.
  A circle that contains your real position is hidden while you are inside it.
* One record can record at a time. Pause, stop and start again from the record list.
* A record can be exported when all compulsory accuracy blocks are filled, every provider has at
  least 300 records, and the record is paused or stopped.
* Import CSV files made by this app (or by KHPAL GPS).

## CSV columns

```
run_id,datetime,provider,latitude,longitude,horizontal_acc,network_acc,vertical_acc,
altitude_wgs84,altitude_msl,speed,bearing,bearing_acc,jitter_m,drift_m
```

* provider: network, gps or fused. One line is one fix of one provider.
* horizontal_acc / vertical_acc: the accuracy of that provider's fix, in meters.
* network_acc: the accuracy of the network position at that moment (for a network line: its own).
* altitude_wgs84 / altitude_msl: altitude above the ellipsoid and above mean sea level, in meters.
* speed: meters per second.
* bearing: direction of travel in degrees from true north, empty when Android reports none.
* bearing_acc: accuracy of the bearing in degrees, empty when not reported.
* jitter_m: meters between this fix and the previous fix of the same provider
  (empty for the first fix, or when the previous fix is older than a minute).
* drift_m: meters between this fix and the average position of the last 20 fixes of the same provider
  (empty until 3 fixes exist).

Older 11-column files (without bearing, bearing_acc, jitter_m and drift_m) still import.


Without keystore secrets the APK is debug-signed and installs on any phone. To get a signed release APK,
add the repository secrets KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD.

To change the app later, upload `splsrec-update.zip` on the main page of the repository.

## License

Based on the SPLS code of KHPAL GPS, which is a fork of GPS Setter. See LICENSE and NOTICE.
