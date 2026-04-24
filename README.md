# Is Nature Reserve?

A minimal Android app that tells you whether you're standing inside an Israeli nature reserve or national park.

Even with a map open, it can be tricky at times to tell whether you're inside a nature reserve (or a national park) and thus, whether or not you'll be breaking any law by camping outside a designated campground. This app tries to do just that.

When you are inside a reserve, the app shows:
- The reserve/park name
- Distance to the nearest exit (boundary edge)
- A live compass arrow pointing toward the nearest exit

## Data

Reserve and national park boundaries come from the [INPA Boundaries](https://github.com/almog/inpa-boundaries) dataset, originally published by the Israel Nature and Parks Authority.

The bundled GeoJSON (`app/src/main/assets/inpa_reserves.geojson`) contains polygon geometries for all declared nature reserves and national parks.

## Building

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires Android SDK with compileSdk 35 and JDK 17.

## Permissions

- **Fine / Coarse Location** — required to determine whether you're inside a reserve boundary.

## License

Boundary data is provided by INPA under the terms described in the [inpa-boundaries](https://github.com/almog/inpa-boundaries) repository.
