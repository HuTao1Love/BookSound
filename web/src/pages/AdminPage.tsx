import DeleteIcon from "@mui/icons-material/Delete";
import KeyIcon from "@mui/icons-material/Key";
import {
  Alert, Button, Card, CardContent, Checkbox, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, IconButton, List, ListItem,
  LinearProgress, ListItemText, MenuItem, Stack, Switch, TextField, Typography,
} from "@mui/material";
import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { api, paths } from "../api/client";
import { PageHeader } from "../components/Ui";
import { keys, useServerUpdate, useSettings, useUsers, useWatchProfiles } from "../api/hooks";
import type { RescanResultDto, ServerSettingsDto, ServerUpdateDto, StatusDto, UserDto, WatchProfileDto, WatchProfileMode } from "../api/types.gen";

export default function AdminPage() {
  const { t } = useTranslation();
  return (
    <Stack spacing={3}>
      <PageHeader title={t("nav.admin")} subtitle={t("admin.subtitle")} />
      <UsersCard />
      <SettingsCard />
      <UpdateCard />
      <ProfilesCard />
    </Stack>
  );
}

function UsersCard() {
  const { t } = useTranslation();
  const users = useUsers();
  const client = useQueryClient();
  const [form, setForm] = useState({ name: "", password: "", isAdmin: false });
  const [error, setError] = useState<string | null>(null);
  const [passwordFor, setPasswordFor] = useState<UserDto | null>(null);
  const [newPassword, setNewPassword] = useState("");
  const refresh = () => client.invalidateQueries({ queryKey: keys.users });
  const run = async (action: () => Promise<unknown>) => {
    try {
      await action();
      setError(null);
      void refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };
  return (
    <Card variant="outlined">
      <CardContent>
        <Typography variant="h6">{t("admin.users")}</Typography>
        {error && <Alert severity="error" onClose={() => setError(null)}>{error}</Alert>}
        <List>
          {users.data?.map((u) => (
            <ListItem key={u.id} disableGutters secondaryAction={
              <>
                <IconButton aria-label={t("admin.resetPassword")} onClick={() => setPasswordFor(u)}><KeyIcon /></IconButton>
                <IconButton aria-label={t("common.delete")} onClick={() => void run(() => api.delete(`${paths.users}/${u.id}`))}><DeleteIcon /></IconButton>
              </>
            }>
              <FormControlLabel
                control={<Checkbox checked={u.isAdmin} onChange={(e) => void run(() => api.patch(`${paths.users}/${u.id}`, { isAdmin: e.target.checked }))} />}
                label={t("admin.isAdmin")}
                sx={{ mr: 1 }}
              />
              <ListItemText primary={u.name} />
            </ListItem>
          ))}
        </List>
        <Typography variant="subtitle2" sx={{ mt: 1 }}>{t("admin.newUser")}</Typography>
        <Stack direction={{ xs: "column", sm: "row" }} spacing={1} sx={{ mt: 1, alignItems: { sm: "center" } }}>
          <TextField size="small" label={t("login.username")} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          <TextField size="small" type="password" label={t("login.password")} autoComplete="new-password" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} />
          <FormControlLabel control={<Checkbox checked={form.isAdmin} onChange={(e) => setForm({ ...form, isAdmin: e.target.checked })} />} label={t("admin.isAdmin")} />
          <Button variant="contained" disabled={!form.name.trim() || form.password.length < 8} onClick={() => void run(async () => {
            await api.post(paths.users, form);
            setForm({ name: "", password: "", isAdmin: false });
          })}>{t("common.add")}</Button>
        </Stack>
      </CardContent>
      <Dialog open={!!passwordFor} onClose={() => setPasswordFor(null)}>
        <DialogTitle>{t("admin.resetPassword")}: {passwordFor?.name}</DialogTitle>
        <DialogContent>
          <TextField autoFocus fullWidth type="password" autoComplete="new-password" label={t("account.newPassword")} helperText={t("login.passwordHint")} value={newPassword} onChange={(e) => setNewPassword(e.target.value)} sx={{ mt: 1 }} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setPasswordFor(null)}>{t("common.cancel")}</Button>
          <Button variant="contained" disabled={newPassword.length < 8} onClick={() => void run(async () => {
            await api.patch(`${paths.users}/${passwordFor!.id}`, { password: newPassword });
            setPasswordFor(null);
            setNewPassword("");
          })}>{t("common.save")}</Button>
        </DialogActions>
      </Dialog>
    </Card>
  );
}

function SettingsCard() {
  const { t } = useTranslation();
  const settings = useSettings();
  const client = useQueryClient();
  const [form, setForm] = useState<ServerSettingsDto | null>(null);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);
  useEffect(() => {
    if (settings.data) setForm(settings.data);
  }, [settings.data]);
  if (!form) return null;
  const number = (key: keyof ServerSettingsDto, label: string) => (
    <TextField size="small" type="number" label={label} value={form[key] as number} onChange={(e) => setForm({ ...form, [key]: Number(e.target.value) })} />
  );
  return (
    <Card variant="outlined">
      <CardContent>
        <Stack spacing={1.5} sx={{ maxWidth: 420 }}>
          <Typography variant="h6">{t("admin.settings")}</Typography>
          {message && <Alert severity={message.ok ? "success" : "error"} onClose={() => setMessage(null)}>{message.text}</Alert>}
          <TextField select size="small" label={t("admin.bitrate")} value={form.encoderBitrateKbps} onChange={(e) => setForm({ ...form, encoderBitrateKbps: Number(e.target.value) })}>
            {[32, 48, 64, 96, 128].map((b) => <MenuItem key={b} value={b}>{b}</MenuItem>)}
          </TextField>
          <FormControlLabel control={<Switch checked={form.downmixMono} onChange={(e) => setForm({ ...form, downmixMono: e.target.checked })} />} label={t("admin.mono")} />
          {number("parallelImports", t("admin.parallel"))}
          {number("torrentUploadLimitKibps", t("admin.uploadLimit"))}
          {number("torrentListenPort", t("admin.port"))}
          {number("watchCacheLimitMib", t("admin.cacheLimit"))}
          <Stack direction="row" spacing={1}>
            <Button variant="contained" onClick={async () => {
              try {
                const saved = await api.put<ServerSettingsDto>(paths.settings, form);
                client.setQueryData(keys.settings, saved);
                setMessage({ ok: true, text: t("admin.saved") });
              } catch (e) {
                setMessage({ ok: false, text: e instanceof Error ? e.message : String(e) });
              }
            }}>{t("common.save")}</Button>
            <Button onClick={async () => {
              const r = await api.post<RescanResultDto>(paths.rescan);
              setMessage({ ok: true, text: t("admin.rescanned", { ...r }) });
              void client.invalidateQueries({ queryKey: keys.books });
            }}>{t("admin.rescan")}</Button>
          </Stack>
        </Stack>
      </CardContent>
    </Card>
  );
}

const UPDATE_POLL_MS = 3000;
const UPDATE_TIMEOUT_MS = 5 * 60 * 1000;

/** The server's version against the latest release; installs it and waits for the server to come back with it. */
function UpdateCard() {
  const { t } = useTranslation();
  const update = useServerUpdate();
  const client = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [checking, setChecking] = useState(false);
  const [copied, setCopied] = useState(false);
  // The version being replaced while an update runs, and when it started.
  const [waiting, setWaiting] = useState<{ from: string; since: number } | null>(null);
  const [doneVersion, setDoneVersion] = useState<string | null>(null);
  const data = update.data;
  const stage = data?.stage;
  const busy = !!waiting || stage === "REQUESTED" || stage === "INSTALLING";

  // An update requested from another tab (or before a reload) is waited for too.
  useEffect(() => {
    if (data && (stage === "REQUESTED" || stage === "INSTALLING") && !waiting) setWaiting({ from: data.currentVersion, since: Date.now() });
  }, [data, stage, waiting]);

  useEffect(() => {
    if (!waiting) return;
    const timer = window.setInterval(async () => {
      if (Date.now() - waiting.since > UPDATE_TIMEOUT_MS) {
        setWaiting(null);
        setError(t("admin.updateTimeout"));
        return;
      }
      try {
        // The server is down for a while during the update: failed requests are expected.
        const status = await api.get<StatusDto>(paths.status);
        if (status.serverVersion !== waiting.from) {
          setWaiting(null);
          setDoneVersion(status.serverVersion);
          window.setTimeout(() => window.location.reload(), 1500);
          return;
        }
        const now = await api.get<ServerUpdateDto>(paths.update);
        client.setQueryData(keys.update, now);
        if (now.stage === "FAILED") {
          setWaiting(null);
          setError(now.message ?? t("admin.updateFailed"));
        }
      } catch {
        // Restarting.
      }
    }, UPDATE_POLL_MS);
    return () => window.clearInterval(timer);
  }, [waiting, client, t]);

  if (!data) return null;
  const check = async () => {
    setChecking(true);
    setError(null);
    try {
      client.setQueryData(keys.update, await api.get<ServerUpdateDto>(`${paths.update}?refresh=1`));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setChecking(false);
    }
  };
  const install = async () => {
    setError(null);
    try {
      const now = await api.post<ServerUpdateDto>(paths.update);
      client.setQueryData(keys.update, now);
      if (now.stage === "FAILED") setError(now.message ?? t("admin.updateFailed"));
      else setWaiting({ from: now.currentVersion, since: Date.now() });
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };
  // A failure left from an earlier attempt is shown until the next one.
  const failure = error ?? (stage === "FAILED" && !busy ? data.message ?? t("admin.updateFailed") : null);
  return (
    <Card variant="outlined">
      <CardContent>
        <Stack spacing={1.5} sx={{ maxWidth: 560 }}>
          <Typography variant="h6">{t("admin.update")}</Typography>
          <Typography variant="body2">
            {t("admin.updateVersion", { version: data.currentVersion })}
            {" · "}
            {data.latestVersion == null ? t("admin.updateUnknown")
              : data.available ? t("admin.updateLatest", { version: data.latestVersion })
              : t("admin.updateUpToDate")}
          </Typography>
          {failure && <Alert severity="error" onClose={() => setError(null)} sx={{ whiteSpace: "pre-wrap" }}>{failure}</Alert>}
          {doneVersion && <Alert severity="success">{t("admin.updateDone", { version: doneVersion })}</Alert>}
          {busy && !doneVersion && (
            <Stack spacing={1}>
              <Typography variant="body2" color="text.secondary">{t("admin.updateInstalling")}</Typography>
              <LinearProgress />
            </Stack>
          )}
          {data.method === "MANUAL" && data.available && data.command && (
            <Stack spacing={1}>
              <Typography variant="body2" color="text.secondary">{t("admin.updateManual")}</Typography>
              <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                <Typography component="code" variant="body2" sx={{ fontFamily: "monospace", bgcolor: "action.hover", px: 1, py: 0.5, borderRadius: 1, overflowX: "auto", whiteSpace: "nowrap", flex: 1, minWidth: 0 }}>
                  {data.command}
                </Typography>
                <Button size="small" onClick={() => {
                  void navigator.clipboard?.writeText(data.command!).then(() => setCopied(true));
                }}>{copied ? t("admin.copied") : t("admin.copy")}</Button>
              </Stack>
            </Stack>
          )}
          <Stack direction="row" spacing={1}>
            {data.method !== "MANUAL" && data.available && (
              <Button variant="contained" disabled={busy || !!doneVersion} onClick={() => void install()}>
                {t("admin.updateInstall")}
              </Button>
            )}
            <Button disabled={checking || busy} onClick={() => void check()}>{t("admin.updateCheck")}</Button>
          </Stack>
        </Stack>
      </CardContent>
    </Card>
  );
}

const MODES: WatchProfileMode[] = ["ON_DEMAND", "ALL_BOOKS", "IN_PROGRESS"];
const RATES = [16000, 22050, 24000, 32000, 44100, 48000];

function ProfilesCard() {
  const { t } = useTranslation();
  const profiles = useWatchProfiles();
  const client = useQueryClient();
  const [editing, setEditing] = useState<WatchProfileDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const blank: WatchProfileDto = { id: "", name: "", bitrateKbps: 32, mono: true, sampleRate: 22050, coverSize: 256, mode: "ON_DEMAND" };
  const save = async () => {
    try {
      await api.post(paths.watchProfiles, editing);
      setEditing(null);
      void client.invalidateQueries({ queryKey: keys.watchProfiles });
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };
  return (
    <Card variant="outlined">
      <CardContent>
        <Stack direction="row" sx={{ alignItems: "center" }}>
          <Typography variant="h6" sx={{ flex: 1 }}>{t("admin.watchProfiles")}</Typography>
          <Button onClick={() => setEditing(blank)}>{t("admin.newProfile")}</Button>
        </Stack>
        <List>
          {profiles.data?.map((p) => (
            <ListItem key={p.id} disableGutters secondaryAction={
              <IconButton aria-label={t("common.delete")} onClick={async () => {
                await api.delete(`${paths.watchProfiles}/${p.id}`);
                void client.invalidateQueries({ queryKey: keys.watchProfiles });
              }}><DeleteIcon /></IconButton>
            }>
              <ListItemText
                sx={{ cursor: "pointer" }}
                onClick={() => setEditing(p)}
                primary={p.name}
                secondary={`${p.bitrateKbps} kbps · ${p.mono ? "mono" : "stereo"} · ${p.sampleRate ? `${p.sampleRate} Hz` : t("admin.profileRateKeep")} · ${t(`admin.modes.${p.mode}`)}`}
              />
            </ListItem>
          ))}
        </List>
      </CardContent>
      <Dialog open={!!editing} onClose={() => setEditing(null)} fullWidth maxWidth="xs">
        <DialogTitle>{editing?.id ? editing.name : t("admin.newProfile")}</DialogTitle>
        {editing && (
          <DialogContent>
            <Stack spacing={2} sx={{ pt: 1 }}>
              {error && <Alert severity="error">{error}</Alert>}
              <TextField size="small" label={t("admin.profileName")} value={editing.name} onChange={(e) => setEditing({ ...editing, name: e.target.value })} />
              <TextField select size="small" label={t("admin.profileBitrate")} value={editing.bitrateKbps} onChange={(e) => setEditing({ ...editing, bitrateKbps: Number(e.target.value) })}>
                {[16, 24, 32, 40, 48, 64, 96, 128].map((b) => <MenuItem key={b} value={b}>{b}</MenuItem>)}
              </TextField>
              <FormControlLabel control={<Switch checked={editing.mono} onChange={(e) => setEditing({ ...editing, mono: e.target.checked })} />} label={t("admin.profileMono")} />
              <TextField select size="small" label={t("admin.profileRate")} value={editing.sampleRate ?? 0} onChange={(e) => setEditing({ ...editing, sampleRate: Number(e.target.value) || null })}>
                <MenuItem value={0}>{t("admin.profileRateKeep")}</MenuItem>
                {RATES.map((r) => <MenuItem key={r} value={r}>{r} Hz</MenuItem>)}
              </TextField>
              <TextField size="small" type="number" label={t("admin.profileCover")} value={editing.coverSize} onChange={(e) => setEditing({ ...editing, coverSize: Number(e.target.value) })} />
              <TextField select size="small" label={t("admin.profileMode")} value={editing.mode} onChange={(e) => setEditing({ ...editing, mode: e.target.value as WatchProfileMode })}>
                {MODES.map((m) => <MenuItem key={m} value={m}>{t(`admin.modes.${m}`)}</MenuItem>)}
              </TextField>
            </Stack>
          </DialogContent>
        )}
        <DialogActions>
          <Button onClick={() => setEditing(null)}>{t("common.cancel")}</Button>
          <Button variant="contained" onClick={() => void save()}>{t("common.save")}</Button>
        </DialogActions>
      </Dialog>
    </Card>
  );
}
