# CS2 Mobile Android alpha

Single-purpose Android launcher for Counter-Strike 2 using the open-source Winlator runtime stack.

Flow: first launch prepares the runtime -> install Steam -> sign in -> install CS2 (AppID 730) -> PLAY launches CS2 with the bundled FPS touch profile at a conservative 960x540 target.

The APK does **not** bundle Counter-Strike 2, Steam credentials, or Valve game assets. Steam handles authentication and game delivery.

Runtime base is pinned to `brunodev85/winlator-app` commit `3981d86efa4f333b2a34a7da8b6521476cd8c8b9` (Winlator 11.2 source line). Keep upstream LGPL notices and source availability when redistributing binaries.


Current Android alpha: 0.4.4. First-run setup now requests Android permissions before preparing the runtime; the launcher keeps normal sensor orientation until Steam/CS2 starts.

0.4.6: upgrade-safe permission onboarding; normal launcher orientation; landscape only for Steam/CS2.
