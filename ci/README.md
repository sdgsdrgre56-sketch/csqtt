# CI signing

`test-signing.p12` is a **public test key** (password `mayak-test`, alias `mayak-test`).
It only exists so that test builds made by GitHub Actions keep the same signature and can be
installed over each other. Anyone can read it, so it must not be used for builds that are
handed out to other people.

For real builds add these repository secrets (Settings → Secrets and variables → Actions):

- `SIGNING_KEYSTORE_B64` — the private keystore, base64-encoded
- `SIGNING_KEYSTORE_PASSWORD` — its password
- `SIGNING_KEY_ALIAS` — the key alias

When `SIGNING_KEYSTORE_B64` is set, the workflow signs with it instead of the test key.
