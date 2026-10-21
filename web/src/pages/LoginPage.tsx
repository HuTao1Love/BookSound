import { Alert, Box, Button, Card, CardContent, Stack, TextField, Typography } from "@mui/material";
import { useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { api, ApiError, paths } from "../api/client";
import { keys } from "../api/hooks";
import type { SessionDto } from "../api/types.gen";
import { Logo } from "../components/Ui";

export default function LoginPage({ setup }: { setup: boolean }) {
  const { t } = useTranslation();
  const client = useQueryClient();
  const navigate = useNavigate();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [token, setToken] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (setup) await api.post(paths.setup, { setupToken: token, username, password });
      const session = await api.post<SessionDto>(paths.webLogin, { username, password });
      client.setQueryData(keys.me, session);
      await client.invalidateQueries({ queryKey: keys.status });
      navigate("/", { replace: true });
    } catch (err) {
      if (err instanceof ApiError && err.code === "too_many_attempts") setError(t("login.locked"));
      else if (err instanceof ApiError && (err.code === "invalid_credentials" || err.status === 401)) setError(setup ? err.message : t("login.invalid"));
      else setError(err instanceof Error ? err.message : t("common.error"));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Box
      sx={(th) => ({
        minHeight: "100dvh", display: "grid", placeItems: "center", p: 2,
        background: `radial-gradient(50% 45% at 25% 15%, ${th.palette.gradient.from}26, transparent), radial-gradient(45% 40% at 85% 85%, ${th.palette.gradient.to}1f, transparent)`,
      })}
    >
      <Card sx={{ width: "100%", maxWidth: 420, backdropFilter: "blur(12px)" }}>
        <CardContent sx={{ p: { xs: 3, sm: 4 } }}>
          <Stack component="form" spacing={2} onSubmit={submit}>
            <Stack spacing={1.5} sx={{ alignItems: "center", textAlign: "center", pb: 1 }}>
              <Logo size={64} />
              <Typography variant="h5">{setup ? t("login.setupTitle") : t("appName")}</Typography>
              {!setup && <Typography color="text.secondary">{t("login.tagline")}</Typography>}
            </Stack>
            {setup && <Typography color="text.secondary">{t("login.setupHint")}</Typography>}
            {error && <Alert severity="error">{error}</Alert>}
            {setup && <TextField label={t("login.setupToken")} value={token} onChange={(e) => setToken(e.target.value)} required autoComplete="off" />}
            <TextField label={t("login.username")} value={username} onChange={(e) => setUsername(e.target.value)} required autoComplete="username" autoFocus />
            <TextField
              label={t("login.password")}
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              autoComplete={setup ? "new-password" : "current-password"}
              helperText={setup ? t("login.passwordHint") : undefined}
            />
            <Button type="submit" variant="contained" size="large" disabled={busy}>
              {setup ? t("login.setupSubmit") : t("login.submit")}
            </Button>
          </Stack>
        </CardContent>
      </Card>
    </Box>
  );
}
