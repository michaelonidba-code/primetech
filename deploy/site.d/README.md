# Server-local Caddy config

`*.caddy` files here are imported by `deploy/Caddyfile` and ignored by git, so settings that only
apply to one server survive `git pull`. For example, to redirect `www.` to the bare domain, create
`deploy/site.d/www.caddy`:

```
www.{$DASHBOARD_DOMAIN} {
	redir https://{$DASHBOARD_DOMAIN}{uri} permanent
}
```

Point the `www` DNS record at the server too, then re-run `sudo ./deploy/install.sh <domain>`.
