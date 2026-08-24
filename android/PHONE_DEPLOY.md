# Installing Bot Lisa on a real phone

Quick reference for getting the app onto a physical Android phone (not the
emulator) and updating it later. Assumes the backend is already deployed to
DigitalOcean Kubernetes (see `infra/terraform/main.tf` and `infra/k8s/`) and
its Load Balancer IP is known.

## One-time tool setup (Mac)

```bash
brew install openjdk@17          # Gradle needs a JDK; Android Studio bundles
                                  # its own, but the command line does not.
echo 'export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
java -version                    # should print openjdk 17.x
```

## Build the APK

```bash
cd android
./gradlew assembleDebug
```

Output lands at `android/app/build/outputs/apk/debug/app-debug.apk`. First
build takes a few minutes; later builds are fast (incremental).

## Get it onto the phone

No USB/cable needed. Upload `app-debug.apk` to Google Drive (or Dropbox,
email, etc.) from your Mac, then open the same app on the phone and tap the
file to download + open it.

On the phone's first install attempt, Android will prompt to allow installs
from that source (e.g. "Allow from Drive") — approve it, then **Install**,
then **Open**.

## Configure it

In the app, tap **Server settings** and enter:

- **Server URL** — `http://<orchestration-service external IP>:8002`
  (`kubectl get service orchestration-service` to look it up)
- **API key** — the value of `orchestration-api-key` in the cluster's
  `bot-lisa-secrets` Secret:
  ```bash
  kubectl get secret bot-lisa-secrets -o jsonpath='{.data.orchestration-api-key}' | base64 -d
  ```

Both fields save automatically (`SharedPreferences`) — no rebuild needed to
change them later, e.g. if the Load Balancer IP or key ever changes.

## Updating the app later

**If only the backend changed** (Python code, k8s manifests): no APK rebuild
needed — rebuild/push the Docker image and redeploy as usual. The app just
talks to whatever's running at the configured URL.

**If the Android code changed** (`MainActivity.kt`, `ApiClient.kt`,
`ServerConfig.kt`, or the manifest): rebuild the APK and reinstall —
```bash
cd android
./gradlew assembleDebug
```
then repeat the "get it onto the phone" step above. Reinstalling over an
existing install keeps saved server settings (same `SharedPreferences`),
so you won't need to re-enter the URL/key unless you uninstalled first.

---

## Aha's — things that weren't obvious going in

A few real gotchas from getting this working end-to-end, worth knowing
before you hit them again:

1. **`kubectl rollout restart` does not pick up YAML changes.** It only
   restarts pods on the *currently applied* spec. A change to a manifest
   (env vars, Service `type: LoadBalancer`, etc.) needs `kubectl apply -f
   infra/k8s/` first — restart alone silently does nothing for that change.

2. **`kubectl rollout restart` also does not pull a new image** if the tag
   string in the manifest didn't change (we always use `:latest`). Pushing a
   new image to the same tag requires an explicit restart to force a
   re-pull; skipping the rebuild/push step entirely (easy to do when
   troubleshooting something else) means the *old* code keeps running with
   no error to indicate it.

3. **`kubectl create secret ... --dry-run=client -o yaml | kubectl apply -f
   -` replaces the whole Secret, not just the one key you passed.** Running
   it once per key deletes the others. Always include every key you want to
   keep in one command:
   ```bash
   kubectl create secret generic bot-lisa-secrets \
     --from-literal=ingestion-api-key="$A" \
     --from-literal=orchestration-api-key="$B" \
     --dry-run=client -o yaml | kubectl apply -f -
   ```

4. **Docker on Apple Silicon builds `arm64` by default**, but DigitalOcean's
   Droplet-based Kubernetes nodes run `amd64`. An image pushed without
   `--platform linux/amd64` pulls fine but fails at container start with a
   cryptic "no match for platform in manifest" error.

5. **Shell variables don't survive across terminal tabs/sessions.** A key
   pulled into `$ORCH_KEY` in one terminal is gone in a new tab — re-derive
   it from the source of truth (`kubectl get secret ... | base64 -d`) rather
   than assuming it's still set; an empty variable in a header still "works"
   syntactically but silently sends nothing, which reads as a mysterious
   auth failure rather than an obvious empty-string bug.

6. **A single small node (`s-1vcpu-2gb`) can deadlock itself during a
   rollout.** Default rolling updates try to start a new pod before killing
   the old one; if the node's already near its CPU limit, the new pod sits
   `Pending` forever because the old one (even if broken) is still holding
   its reservation. Fix was reducing `replicas` to 1 per service and
   clearing out stale ReplicaSets by hand when this happened.

7. **The APK doesn't carry your server config.** Server URL and API key
   live in the *installed app's* local storage, set once via "Server
   settings" — not baked into the file. Sharing the APK with someone else
   means separately telling them the URL and key too.
