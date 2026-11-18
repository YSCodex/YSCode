# One-time setup

## 1. Cloudflare (website + backend)
1. Create an API token at <https://dash.cloudflare.com/profile/api-tokens> (*Create Custom Token*) with:
   - Account > Workers Scripts > Edit
   - Account > D1 > Edit
   - Account > Account Settings > Read
2. Copy your **Account ID** (dashboard > Workers & Pages, right column).
3. In this GitHub repo: *Settings > Secrets and variables > Actions > New repository secret*:
   - `CLOUDFLARE_API_TOKEN`
   - `CLOUDFLARE_ACCOUNT_ID`
   - `ADMIN_TOKEN` (already created for you; this is your admin dashboard password)
   - optional: `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID` to get new feedback on Telegram
4. Run *Actions > Deploy Cloudflare > Run workflow* (it also runs on every push to `cloudflare/`).
   It creates the D1 database, applies `schema.sql`, deploys the Worker + site, sets the secrets, and writes the live URL to `config.json`.
5. Open `<your-url>/admin` and sign in with `ADMIN_TOKEN`.

Optional custom domain: Cloudflare dashboard > Workers > yscode > Settings > Domains & Routes.

## 2. Publish a release (from the private build repo)
Run *Actions > Publish public release* in the private repo (`YSCODE-test`) with the version number. It builds and signs the APK,
creates the GitHub release here with the checksum, and updates `latest.json`. The website, `/download` and in-app updates pick it up
within a few minutes.
