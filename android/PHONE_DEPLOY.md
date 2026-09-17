# Installing Bot Lisa on a real phone

Quick reference for getting the app onto a physical Android phone (not the
emulator) and updating it later. Assumes the backend is already deployed to
DigitalOcean Kubernetes (see `infra/terraform/main.tf` and `infra/k8s/`) and
its Load Balancer IP is known.

*In other words: this walks through turning your code into an app file,
getting that file onto your phone, and telling the app where your server
lives on the internet.*

## Deploy over USB (quickest)

Phone plugged in via USB, **USB debugging** enabled (Settings → Developer
options) and the "Allow USB debugging" prompt approved on the phone. Needs
the JDK from [One-time tool setup](#one-time-tool-setup-mac) and `adb`
(bundled with Android Studio, or `brew install android-platform-tools`).

```bash
# build the debug APK
cd android
./gradlew assembleDebug

# list connected devices (ignore the emulator if Android Studio is running one)
adb devices

# install, replacing <device-id> with the id from `adb devices`
adb -s <device-id> install -r app/build/outputs/apk/debug/app-debug.apk
```

APK lands at `android/app/build/outputs/apk/debug/app-debug.apk`. First build
takes a few minutes; later builds are incremental and fast. `install -r`
reinstalls over the existing app, keeping saved server settings.

*In other words: with a cable you skip the upload-and-download dance — one
command builds the app, one lists your phone, one pushes it straight on.*

## One-time tool setup (Mac)

```bash
brew install openjdk@17          # Gradle needs a JDK; Android Studio bundles
                                  # its own, but the command line does not.
echo 'export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
java -version                    # should print openjdk 17.x
```

*In other words: the tool that builds Android apps (Gradle) needs a program
called Java installed to run at all — this installs it.*

## Alternative: no cable (Drive / email)

Build the APK (`cd android && ./gradlew assembleDebug`), then upload
`app/build/outputs/apk/debug/app-debug.apk` to Google Drive (or Dropbox,
email, etc.). Open that app on the phone, tap the file to download it, then
open it. On the first install Android prompts to allow installs from that
source (e.g. "Allow from Drive") — approve it, then **Install** → **Open**.

*In other words: no cable handy? Move the file to the phone yourself (like
AirDropping a document) and tell Android "yes, install it anyway" — this app
isn't on the Play Store.*

## Configure it

In the app, tap the **gear** (top right) to open settings, then enter:

- **Server URL** — `http://<orchestration-service external IP>:8002`
  (`kubectl get service orchestration-service` to look it up)
- **API key** — the value of `orchestration-api-key` in the cluster's
  `bot-lisa-secrets` Secret:
  ```bash
  kubectl get secret bot-lisa-secrets -o jsonpath='{.data.orchestration-api-key}' | base64 -d
  ```

Both fields save automatically (`SharedPreferences`) — no rebuild needed to
change them later, e.g. if the Load Balancer IP or key ever changes.

*In other words: the app needs two things typed in once — the web address of
your backend, and a password proving it's really you calling it. Both get
remembered after that.*

## Updating the app later

**If only the backend changed** (Python code, k8s manifests): no APK rebuild
needed — the app just talks to whatever's running at the configured URL, so
the phone doesn't need to know anything happened. What you do need to do is
get the new code running on the cluster:

1. **Confirm you're pointed at the right cluster.**
   ```bash
   doctl kubernetes cluster kubeconfig save bot-lisa-cluster
   kubectl get pods
   ```
   You should see `retrieval-service` and `orchestration-service` pods
   already `Running`. (`ingestion-service` was torn down 2026-09-17 -- it
   was an unused LoadBalancer billing for a paused feature; see
   `infra/k8s/paused/ingestion-service.yaml`.)

   *In other words: this makes sure the commands below land on Bot Lisa's
   cluster and not some other DigitalOcean project you might have.*

2. **Log in to the registry.**
   ```bash
   doctl registry login
   ```

   *In other words: this proves to DigitalOcean's private image storage that
   it's really you pushing to it.*

3. **Build and push the image.** Build from the repo root (not `android/`)
   so the whole `services/` tree is included, and explicitly target the
   cluster's CPU architecture:
   ```bash
   cd ~/repos/bot-lisa
   docker build --platform linux/amd64 -t registry.digitalocean.com/bot-lisa/bot-lisa:latest -f infra/Dockerfile .
   docker push registry.digitalocean.com/bot-lisa/bot-lisa:latest
   ```
   The `--platform linux/amd64` flag matters: Docker on Apple Silicon builds
   `arm64` by default, but the cluster's node runs `amd64`. Skip it and the
   push still succeeds, but the pod fails to start with a cryptic "no match
   for platform in manifest" error.

   *In other words: this turns your code into the packaged, runnable form
   Kubernetes understands, and uploads it. The `--platform` flag is there
   because your Mac and the cloud server speak slightly different machine-code
   dialects — you have to say which one to build for.*

4. **If you only changed Python code, restart the deployments so they pick
   up the new image:**
   ```bash
   kubectl rollout restart deployment/retrieval-service deployment/orchestration-service
   kubectl rollout status deployment/retrieval-service
   kubectl rollout status deployment/orchestration-service
   ```
   **If you also changed a k8s manifest** (`infra/k8s/*.yaml` — env vars, the
   Service type, resource limits, etc.), `apply` it first — a restart alone
   does not pick up manifest changes, only a new image on the same spec:
   ```bash
   kubectl apply -f infra/k8s/
   ```
   The `retrieval-service` pod downloads the ~220MB embedding model
   (`fastembed`, see `services/retrieval_service/embeddings.py`) the moment
   it starts, so the first rollout after that changed may take a minute or
   two longer than usual to go `Ready`. That's expected, not a crash loop.

   *In other words: pushing a new image doesn't automatically restart what's
   already running — you have to tell it to. And "restart" and "apply" do
   different jobs: restart re-pulls the image on the current setup, apply
   changes the setup itself. If you changed the setup, restarting alone
   quietly does nothing.*

5. **Verify before touching the phone.**
   ```bash
   kubectl get svc orchestration-service   # external IP under EXTERNAL-IP
   curl http://<that-ip>:8002/health
   ```
   If `ORCHESTRATION_API_KEY` is set on the cluster, include it on any
   `/assist` test calls:
   ```bash
   curl -H "X-API-Key: $(kubectl get secret bot-lisa-secrets -o jsonpath='{.data.orchestration-api-key}' | base64 -d)" \
     -X POST http://<that-ip>:8002/assist \
     -H "Content-Type: application/json" -d '{"text": "hello"}'
   ```

   *In other words: check the server is actually up and answering correctly
   from the command line, before assuming the phone app will work — it's
   faster to debug here than by poking around in the app.*

Once step 5 looks right, the already-installed app on your phone is talking
to the updated backend automatically — no reinstall needed, same as any
other backend-only change.

**If the Android code changed** (any file under `android/`): rebuild and
reinstall — re-run the [Deploy over USB](#deploy-over-usb-quickest) commands
(or the no-cable alternative). `install -r` keeps saved server settings
(same `SharedPreferences`), so you won't re-enter the URL/key unless you
uninstalled first.

*In other words: changing the server doesn't require touching the phone at
all. Changing the app itself means building a new APK and reinstalling it —
your saved server address/key survive that, no retyping needed.*

---

## Aha's — things that weren't obvious going in

A few real gotchas from getting this working end-to-end, worth knowing
before you hit them again:

1. **`kubectl rollout restart` does not pick up YAML changes.** It only
   restarts pods on the *currently applied* spec. A change to a manifest
   (env vars, Service `type: LoadBalancer`, etc.) needs `kubectl apply -f
   infra/k8s/` first — restart alone silently does nothing for that change.
   *In other words: "restart it" isn't the same as "update it." You have to
   apply your changes first, then restart.*

2. **`kubectl rollout restart` also does not pull a new image** if the tag
   string in the manifest didn't change (we always use `:latest`). Pushing a
   new image to the same tag requires an explicit restart to force a
   re-pull; skipping the rebuild/push step entirely (easy to do when
   troubleshooting something else) means the *old* code keeps running with
   no error to indicate it.
   *In other words: restarting doesn't re-download your code. If you didn't
   rebuild and push first, it just restarts the same old version — quietly,
   with no warning that nothing actually changed.*

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
   *In other words: this command doesn't "add one password" — it replaces the
   whole password box with just what you gave it, throwing out the rest.
   List everything you want kept, every time.*

4. **Docker on Apple Silicon builds `arm64` by default**, but DigitalOcean's
   Droplet-based Kubernetes nodes run `amd64`. An image pushed without
   `--platform linux/amd64` pulls fine but fails at container start with a
   cryptic "no match for platform in manifest" error.
   *In other words: your Mac and the cloud server speak slightly different
   "dialects" of machine code. You have to explicitly tell Docker to build
   in the cloud's dialect, not your Mac's.*

5. **Shell variables don't survive across terminal tabs/sessions.** A key
   pulled into `$ORCH_KEY` in one terminal is gone in a new tab — re-derive
   it from the source of truth (`kubectl get secret ... | base64 -d`) rather
   than assuming it's still set; an empty variable in a header still "works"
   syntactically but silently sends nothing, which reads as a mysterious
   auth failure rather than an obvious empty-string bug.
   *In other words: a value you set in one terminal window is forgotten the
   moment you open a new one — it doesn't carry over like a saved setting
   would.*

6. **A single small node (`s-1vcpu-2gb`) can deadlock itself during a
   rollout.** Default rolling updates try to start a new pod before killing
   the old one; if the node's already near its CPU limit, the new pod sits
   `Pending` forever because the old one (even if broken) is still holding
   its reservation. Fix was reducing `replicas` to 1 per service and
   clearing out stale ReplicaSets by hand when this happened.
   *In other words: our one small server tried to run an old and new copy of
   the app at once during an update, and didn't have enough room for both —
   so it got stuck. We told it to only ever run one copy at a time instead.*

7. **The APK doesn't carry your server config.** Server URL and API key
   live in the *installed app's* local storage, set once via "Server
   settings" — not baked into the file. Sharing the APK with someone else
   means separately telling them the URL and key too.
   *In other words: the app file itself doesn't know your server's address or
   password — that's stored on each phone separately after you type it in.
   Handing someone the file alone isn't enough; they need those two values
   from you as well.*

8. **DigitalOcean's registry has a storage quota, and repeated pushes to
   `:latest` quietly eat it.** Every `docker push` to the same tag orphans
   the previous image's layers (the tag pointer just moves to the new
   manifest; the old blobs stick around using quota until something
   deletes them), and DOCR doesn't run garbage collection on its own --
   nothing warns you as it fills up. Eventually a push just fails outright:
   ```
   error from registry: quota exceeded
   ```
   and at that point you're mid-deploy with no way to push a fix either.

   Fix once it happens:
   ```bash
   doctl registry get                              # confirm it's actually quota, check usage
   doctl registry garbage-collection start bot-lisa # reclaims space; puts the registry read-only
   doctl registry garbage-collection list bot-lisa  # check progress
   ```
   DOCR has to wait for any recent write credentials to expire before it can
   safely delete anything, up to 15 minutes depending on when your last
   push attempt (including a failed one) started -- not a fixed wait, could
   finish sooner. Once `Status` shows `succeeded`, the registry is writable
   again and the same build/push/deploy sequence will go through.

   **What garbage collection actually reclaims can be tiny.** The run that
   fixed this here took ~13.5 minutes total and freed all of 84,434 bytes
   (`Bytes Freed`) -- effectively nothing. That's a real signal, not a fluke:
   it means the quota problem usually isn't "clutter from old pushes,"
   there just wasn't much orphaned/untagged data sitting around to reclaim.
   What was actually eating the quota was the *current*, still-tagged image
   itself (at the time, ~400MB+ with the embedding model baked in) on a
   registry whose total plan size is small (DigitalOcean's free/Starter
   tier is 500MB) to begin with. Garbage collection only ever helps with
   orphaned data from old pushes to the same tag -- it can't shrink the
   image you're actively using.

   Avoid needing this reactively at all: run garbage collection every so
   often as routine maintenance instead of waiting until a push fails --
   but know it may not free much if the *current* image is simply too big
   for the plan, in which case a bigger registry tier is the only real
   fix, not more cleanup. This specific instance was caused by the
   embedding model being briefly baked into the image -- since fixed by
   moving the model to a persistent volume instead (see the PVC in
   infra/k8s/retrieval-service.yaml), so the image is back to its normal
   size and this is a much rarer concern now, not gone forever if a future
   change grows the image again some other way.

   *In other words: DigitalOcean doesn't clean up old versions of your image
   for you, so a small registry plan fills up quietly, push after push,
   until one day it just refuses the next one. Cleaning it up yourself once
   in a while helps with that kind of clutter -- but if the app itself has
   just gotten too big for the plan, cleaning up won't fix that part, only
   paying for more room (or shrinking the app) will.*
