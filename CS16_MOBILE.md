# CS16 Mobile for Android

Source bundle: `cs16-mobile-source.zip`.

The project builds a single Android APK using Xash3D FWGS + the open-source CS16Client native libraries. The launcher supports Steam OpenID in the browser without a Steam Web API key and downloads the user's private game-data bundle from a VPS.

The repository does not include Valve game assets. The VPS setup in the source bundle creates a private bundle from game files obtained through an authenticated Steam/DepotDownloader session.

GitHub Actions workflow: `.github/workflows/cs16-mobile-build.yml`.
