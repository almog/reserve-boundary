# Reserve Boundary

A minimal Android app that tells you whether you're standing inside an Israeli nature reserve or national park, and points you toward the nearest reserve boundary either way.

Even with a map open, it can be tricky at times to tell whether you're inside a nature reserve (or a national park) and thus, whether or not you'll be breaking any law by camping outside a designated campground. This app tries to do just that.

When you are inside a reserve, the app shows:
- The reserve/park name
- Distance to the nearest exit (boundary edge)
- A live compass arrow pointing toward the nearest exit

When you are outside any reserve, the app shows the name and distance of the nearest one, with a compass arrow pointing toward it.

## Data

Reserve and national park boundaries come from the [INPA Boundaries](https://github.com/almog/inpa-boundaries) dataset, originally published by the Israel Nature and Parks Authority.

The bundled GeoJSON (`app/src/main/assets/inpa_reserves.geojson`) contains polygon geometries for all declared nature reserves and national parks.

## Building

Debug build, install on a connected device:

```
./gradlew installDebug
```

Release build (signed AAB for Play Store upload — see [PLAY_STORE_CHECKLIST.md](PLAY_STORE_CHECKLIST.md) for the keystore setup):

```
./gradlew bundleRelease
```

Requires Android SDK with compileSdk 35 and JDK 17.

## Permissions

- **Fine / Coarse Location** — required to test the user's position against reserve polygons. Used only on-device; no location data leaves the phone.

## License

Boundary data is provided by INPA under the terms described in the [inpa-boundaries](https://github.com/almog/inpa-boundaries) repository.
