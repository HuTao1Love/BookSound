import AccountCircleRoundedIcon from "@mui/icons-material/AccountCircleRounded";
import AddCircleRoundedIcon from "@mui/icons-material/AddCircleRounded";
import DnsRoundedIcon from "@mui/icons-material/DnsRounded";
import LibraryBooksRoundedIcon from "@mui/icons-material/LibraryBooksRounded";
import { alpha, Avatar, Badge, Box, ButtonBase, Typography, useMediaQuery, useTheme } from "@mui/material";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { useLocation, useNavigate } from "react-router-dom";
import { useImports, useTorrents, useUploads } from "../api/hooks";
import type { SessionDto } from "../api/types.gen";
import MiniPlayer from "./MiniPlayer";
import { Logo, SIDEBAR_WIDTH } from "./Ui";

const BOTTOM_NAV = 64;
const ACTIVE_JOBS = ["QUEUED", "PREPARING", "CONVERTING", "WRITING", "VERIFYING", "FINISHING"];

/** Navigation on the left on wide screens, at the bottom on phones; the mini player above the content's bottom. */
export default function AppShell({ session, children }: { session: SessionDto; features: string[]; children: ReactNode }) {
  const { t } = useTranslation();
  const theme = useTheme();
  const wide = useMediaQuery(theme.breakpoints.up("md"));
  const location = useLocation();
  const navigate = useNavigate();
  const busy = useBusyCount();

  const tabs = [
    { path: "/", label: t("nav.library"), icon: <LibraryBooksRoundedIcon /> },
    { path: "/add", label: t("nav.imports"), icon: <AddCircleRoundedIcon />, badge: busy },
    ...(session.user.isAdmin ? [{ path: "/admin", label: t("nav.admin"), icon: <DnsRoundedIcon /> }] : []),
    { path: "/account", label: t("nav.account"), icon: <AccountCircleRoundedIcon /> },
  ];
  const current = tabs.find((tab) => tab.path !== "/" && location.pathname.startsWith(tab.path))?.path
    ?? (["/", "/book", "/series"].some((p) => location.pathname === p || location.pathname.startsWith(p + "/")) ? "/" : null);
  const onPlayer = location.pathname === "/player";

  return (
    <Box sx={{ minHeight: "100dvh" }}>
      {wide && (
        <Box
          component="nav"
          sx={{
            position: "fixed", top: 0, bottom: 0, left: 0, width: SIDEBAR_WIDTH, zIndex: 5, display: "flex", flexDirection: "column",
            p: 2, gap: 0.5, bgcolor: "surface.low", borderRight: 1, borderColor: "divider",
          }}
        >
          <ButtonBase onClick={() => navigate("/")} sx={{ justifyContent: "flex-start", gap: 1.5, px: 1.5, py: 1, mb: 2, borderRadius: 3 }}>
            <Logo size={34} />
            <Typography sx={{ fontWeight: 800, fontSize: 19, letterSpacing: "-0.01em" }}>{t("appName")}</Typography>
          </ButtonBase>
          {tabs.map((tab) => {
            const selected = current === tab.path;
            return (
              <ButtonBase
                key={tab.path}
                onClick={() => navigate(tab.path)}
                sx={{
                  justifyContent: "flex-start", gap: 1.5, px: 1.75, py: 1.25, borderRadius: 3, fontWeight: 700, fontSize: 15,
                  color: selected ? "primary.main" : "text.secondary",
                  bgcolor: selected ? alpha(theme.palette.primary.main, 0.12) : "transparent",
                  "&:hover": { bgcolor: selected ? alpha(theme.palette.primary.main, 0.16) : alpha(theme.palette.text.primary, 0.05), color: selected ? "primary.main" : "text.primary" },
                }}
              >
                <Badge badgeContent={tab.badge} color="primary" sx={{ "& .MuiBadge-badge": { fontWeight: 800 } }}>{tab.icon}</Badge>
                {tab.label}
              </ButtonBase>
            );
          })}
          <Box sx={{ flex: 1 }} />
          <ButtonBase onClick={() => navigate("/account")} sx={{ justifyContent: "flex-start", gap: 1.5, p: 1, borderRadius: 3, "&:hover": { bgcolor: alpha(theme.palette.text.primary, 0.05) } }}>
            <Avatar sx={{ width: 34, height: 34, fontSize: 15, fontWeight: 800, background: theme.palette.gradient.css, color: theme.palette.gradient.on }}>
              {session.user.name.slice(0, 1).toUpperCase()}
            </Avatar>
            <Box sx={{ minWidth: 0, textAlign: "left" }}>
              <Typography variant="body2" noWrap sx={{ fontWeight: 700 }}>{session.user.name}</Typography>
              <Typography variant="caption" color="text.secondary" noWrap component="div">{session.user.isAdmin ? t("account.admin") : t("account.user")}</Typography>
            </Box>
          </ButtonBase>
        </Box>
      )}

      <Box
        component="main"
        sx={{
          ml: { md: `${SIDEBAR_WIDTH}px` }, position: "relative",
          pb: wide ? (onPlayer ? 4 : 14) : `calc(${BOTTOM_NAV + (onPlayer ? 16 : 96)}px + env(safe-area-inset-bottom))`,
        }}
      >
        <Box sx={{ position: "relative", zIndex: 1, maxWidth: 1280, mx: "auto", px: { xs: 2, sm: 3, md: 5 }, pt: { xs: 2.5, md: 5 } }}>
          {children}
        </Box>
      </Box>

      {!onPlayer && <MiniPlayer bottomOffset={wide ? 0 : BOTTOM_NAV} />}

      {!wide && (
        <Box
          component="nav"
          sx={{
            position: "fixed", bottom: 0, left: 0, right: 0, zIndex: 6, display: "flex", pb: "env(safe-area-inset-bottom)",
            bgcolor: alpha(theme.palette.surface.low, 0.86), backdropFilter: "blur(18px)", borderTop: 1, borderColor: "divider",
          }}
        >
          {tabs.map((tab) => {
            const selected = current === tab.path;
            return (
              <ButtonBase key={tab.path} onClick={() => navigate(tab.path)} sx={{ flex: 1, height: BOTTOM_NAV, flexDirection: "column", gap: 0.25, color: selected ? "primary.main" : "text.secondary" }}>
                <Box sx={{ px: 2, py: 0.25, borderRadius: 999, bgcolor: selected ? alpha(theme.palette.primary.main, 0.16) : "transparent", display: "flex" }}>
                  <Badge badgeContent={tab.badge} color="primary">{tab.icon}</Badge>
                </Box>
                <Typography variant="caption" sx={{ fontWeight: 700, fontSize: 11.5 }}>{tab.label}</Typography>
              </ButtonBase>
            );
          })}
        </Box>
      )}
    </Box>
  );
}

/** Imports, torrents and uploads under way: the badge on "Add books". */
function useBusyCount(): number {
  const imports = useImports();
  const torrents = useTorrents();
  const uploads = useUploads();
  return (imports.data?.filter((j) => ACTIVE_JOBS.includes(j.stage)).length ?? 0)
    + (torrents.data?.filter((t) => t.phase !== "COMPLETED" && t.phase !== "FAILED").length ?? 0)
    + (uploads.data?.length ?? 0);
}
