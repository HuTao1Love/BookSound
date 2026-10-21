import PauseRoundedIcon from "@mui/icons-material/PauseRounded";
import PlayArrowRoundedIcon from "@mui/icons-material/PlayArrowRounded";
import { Box, ButtonBase, Stack, Typography, type SxProps, type Theme } from "@mui/material";
import type { MouseEvent, ReactNode } from "react";

/** Width of the navigation on wide screens. */
export const SIDEBAR_WIDTH = 248;

/** The app's mark: the phone's launcher icon. */
export function Logo({ size = 32 }: { size?: number }) {
  return <Box component="img" src="/icon.svg" alt="" sx={{ width: size, height: size, borderRadius: size / 4.5, display: "block" }} />;
}

/** A page's title, a line under it, and actions on the right. */
export function PageHeader({ title, subtitle, actions }: { title: ReactNode; subtitle?: ReactNode; actions?: ReactNode }) {
  return (
    <Stack direction={{ xs: "column", sm: "row" }} spacing={1.5} sx={{ alignItems: { sm: "flex-end" }, justifyContent: "space-between", mb: 3 }}>
      <Box sx={{ minWidth: 0 }}>
        <Typography variant="h4" sx={{ fontSize: { xs: 28, md: 34 } }}>{title}</Typography>
        {subtitle && <Typography color="text.secondary" sx={{ mt: 0.5 }}>{subtitle}</Typography>}
      </Box>
      {actions && <Stack direction="row" spacing={1} sx={{ flexShrink: 0, flexWrap: "wrap", gap: 1 }}>{actions}</Stack>}
    </Stack>
  );
}

/** A small caps heading over a group. */
export function SectionTitle({ children, action }: { children: ReactNode; action?: ReactNode }) {
  return (
    <Stack direction="row" sx={{ alignItems: "center", justifyContent: "space-between", mb: 1.5, minHeight: 32 }}>
      <Typography variant="overline" color="text.secondary" sx={{ lineHeight: 1 }}>{children}</Typography>
      {action}
    </Stack>
  );
}

/** The round play button with the brand gradient. */
export function PlayButton({ playing, size = 56, onClick, label, className, sx }: { playing?: boolean; size?: number; onClick: (e: MouseEvent) => void; label?: string; className?: string; sx?: SxProps<Theme> }) {
  return (
    <ButtonBase
      onClick={onClick}
      className={className}
      aria-label={label ?? (playing ? "pause" : "play")}
      sx={[
        (t) => ({
          width: size, height: size, borderRadius: "50%", flexShrink: 0, background: t.palette.gradient.css, color: t.palette.gradient.on,
          boxShadow: `0 10px 28px -8px ${t.palette.gradient.to}99`, transition: "transform 120ms, filter 120ms",
          "&:hover": { filter: "brightness(1.07)" }, "&:active": { transform: "scale(0.95)" },
        }),
        ...(Array.isArray(sx) ? sx : [sx]),
      ]}
    >
      {playing ? <PauseRoundedIcon sx={{ fontSize: size * 0.55 }} /> : <PlayArrowRoundedIcon sx={{ fontSize: size * 0.6 }} />}
    </ButtonBase>
  );
}

/** A thin progress line with the brand gradient. */
export function ProgressLine({ value, height = 4, sx }: { value: number; height?: number; sx?: SxProps<Theme> }) {
  return (
    <Box sx={[(t) => ({ height, borderRadius: height, bgcolor: t.palette.mode === "dark" ? "rgba(255,255,255,0.1)" : "rgba(0,0,0,0.08)", overflow: "hidden" }), ...(Array.isArray(sx) ? sx : [sx])]}>
      <Box sx={(t) => ({ width: `${Math.min(1, Math.max(0, value)) * 100}%`, height: "100%", background: t.palette.gradient.css, transition: "width 300ms linear" })} />
    </Box>
  );
}

/** Centered text when a list has nothing to show. */
export function EmptyState({ icon, title, hint, action }: { icon?: ReactNode; title: ReactNode; hint?: ReactNode; action?: ReactNode }) {
  return (
    <Stack spacing={1} sx={{ alignItems: "center", textAlign: "center", py: { xs: 6, md: 10 }, px: 2 }}>
      {icon && <Box sx={{ color: "text.secondary", "& svg": { fontSize: 48 }, mb: 1 }}>{icon}</Box>}
      <Typography variant="h6">{title}</Typography>
      {hint && <Typography color="text.secondary" sx={{ maxWidth: 440 }}>{hint}</Typography>}
      {action && <Box sx={{ pt: 1 }}>{action}</Box>}
    </Stack>
  );
}
