import AccountCircleIcon from "@mui/icons-material/AccountCircle";
import AddCircleIcon from "@mui/icons-material/AddCircle";
import DnsIcon from "@mui/icons-material/Dns";
import LibraryBooksIcon from "@mui/icons-material/LibraryBooks";
import { AppBar, BottomNavigation, BottomNavigationAction, Box, Container, Paper, Tab, Tabs, Toolbar, Typography, useMediaQuery, useTheme } from "@mui/material";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { useLocation, useNavigate } from "react-router-dom";
import type { SessionDto } from "../api/types.gen";
import MiniPlayer from "./MiniPlayer";

export default function AppShell({ session, children }: { session: SessionDto; features: string[]; children: ReactNode }) {
  const { t } = useTranslation();
  const theme = useTheme();
  const wide = useMediaQuery(theme.breakpoints.up("md"));
  const location = useLocation();
  const navigate = useNavigate();

  const tabs = [
    { path: "/", label: t("nav.library"), icon: <LibraryBooksIcon /> },
    { path: "/add", label: t("nav.imports"), icon: <AddCircleIcon /> },
    ...(session.user.isAdmin ? [{ path: "/admin", label: t("nav.admin"), icon: <DnsIcon /> }] : []),
    { path: "/account", label: t("nav.account"), icon: <AccountCircleIcon /> },
  ];
  const current = tabs.find((tab) => tab.path !== "/" && location.pathname.startsWith(tab.path))?.path ?? (["/", "/book", "/series"].some((p) => location.pathname === p || location.pathname.startsWith(p + "/")) ? "/" : false);
  const onPlayer = location.pathname === "/player";

  return (
    <Box sx={{ minHeight: "100dvh", display: "flex", flexDirection: "column" }}>
      {wide && (
        <AppBar position="sticky" color="inherit" elevation={0} sx={{ borderBottom: 1, borderColor: "divider" }}>
          <Toolbar sx={{ gap: 3 }}>
            <Typography variant="h6" sx={{ fontWeight: 600, cursor: "pointer" }} onClick={() => navigate("/")}>
              {t("appName")}
            </Typography>
            <Tabs value={current} onChange={(_, v) => navigate(v)} sx={{ flex: 1 }}>
              {tabs.map((tab) => (
                <Tab key={tab.path} value={tab.path} label={tab.label} icon={tab.icon} iconPosition="start" sx={{ minHeight: 64 }} />
              ))}
            </Tabs>
            <Typography variant="body2" color="text.secondary">
              {session.user.name}
            </Typography>
          </Toolbar>
        </AppBar>
      )}
      <Container maxWidth="lg" sx={{ flex: 1, py: { xs: 2, md: 3 }, px: { xs: 2, md: 3 }, pb: { xs: onPlayer ? 2 : 20, md: onPlayer ? 3 : 14 } }}>
        {children}
      </Container>
      {!onPlayer && <MiniPlayer bottomOffset={wide ? 0 : 56} />}
      {!wide && (
        <Paper sx={{ position: "fixed", bottom: 0, left: 0, right: 0, zIndex: 3, pb: "env(safe-area-inset-bottom)" }} elevation={6}>
          <BottomNavigation value={current} onChange={(_, v) => navigate(v)} showLabels>
            {tabs.map((tab) => (
              <BottomNavigationAction key={tab.path} value={tab.path} label={tab.label} icon={tab.icon} />
            ))}
          </BottomNavigation>
        </Paper>
      )}
    </Box>
  );
}
