import { Alert, Button, Card, CardContent, IconButton, List, ListItem, ListItemText, Stack, TextField, Typography } from "@mui/material";
import LogoutIcon from "@mui/icons-material/Logout";
import { useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { api, paths } from "../api/client";
import { keys, useDevices } from "../api/hooks";
import type { SessionDto } from "../api/types.gen";
import { PageHeader } from "../components/Ui";

export default function AccountPage({ session }: { session: SessionDto }) {
  const { t, i18n } = useTranslation();
  const devices = useDevices();
  const client = useQueryClient();
  const [passwords, setPasswords] = useState({ current: "", next: "" });
  const [passwordResult, setPasswordResult] = useState<{ ok: boolean; text: string } | null>(null);
  const when = (ms: number) => new Date(ms).toLocaleString(i18n.language);

  const signOut = async () => {
    await api.post(paths.logout).catch(() => {});
    client.setQueryData(keys.me, null);
    window.location.href = "/login";
  };

  return (
    <Stack spacing={3}>
      <PageHeader
        title={session.user.name}
        subtitle={session.user.isAdmin ? t("account.admin") : t("account.user")}
        actions={<Button variant="outlined" startIcon={<LogoutIcon />} onClick={() => void signOut()}>{t("account.signOut")}</Button>}
      />

      <Card variant="outlined">
        <CardContent>
          <Typography variant="h6">{t("account.devices")}</Typography>
          <List>
            {devices.data?.map((d) => (
              <ListItem key={d.id} disableGutters secondaryAction={!d.current && (
                <IconButton edge="end" aria-label={t("account.revoke")} onClick={async () => {
                  await api.delete(`${paths.devices}/${d.id}`);
                  void client.invalidateQueries({ queryKey: keys.devices });
                }}><LogoutIcon /></IconButton>
              )}>
                <ListItemText
                  primary={`${d.name}${d.current ? ` (${t("account.thisDevice")})` : ""}`}
                  secondary={`${t(`account.kind.${d.kind}`)} · ${t("account.lastSeen", { when: when(d.lastSeenAt ?? d.createdAt) })}`}
                />
              </ListItem>
            ))}
          </List>
        </CardContent>
      </Card>

      <Card variant="outlined">
        <CardContent>
          <Stack spacing={1.5} sx={{ maxWidth: 360 }}>
            <Typography variant="h6">{t("account.password")}</Typography>
            {passwordResult && <Alert severity={passwordResult.ok ? "success" : "error"}>{passwordResult.text}</Alert>}
            <TextField size="small" type="password" label={t("account.currentPassword")} autoComplete="current-password" value={passwords.current} onChange={(e) => setPasswords({ ...passwords, current: e.target.value })} />
            <TextField size="small" type="password" label={t("account.newPassword")} autoComplete="new-password" helperText={t("login.passwordHint")} value={passwords.next} onChange={(e) => setPasswords({ ...passwords, next: e.target.value })} />
            <Button variant="contained" disabled={!passwords.current || passwords.next.length < 8} onClick={async () => {
              try {
                await api.post(paths.password, { currentPassword: passwords.current, newPassword: passwords.next });
                setPasswordResult({ ok: true, text: t("account.changed") });
                setPasswords({ current: "", next: "" });
              } catch (e) {
                setPasswordResult({ ok: false, text: e instanceof Error ? e.message : String(e) });
              }
            }} sx={{ alignSelf: "flex-start" }}>{t("common.save")}</Button>
          </Stack>
        </CardContent>
      </Card>
    </Stack>
  );
}
