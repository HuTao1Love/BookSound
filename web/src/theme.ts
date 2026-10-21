import { alpha, createTheme, type Theme } from "@mui/material";

/*
 * "Dusk", the phone app's look: ink-blue surfaces with an apricot→rose accent in the dark, warm paper with
 * terracotta in the light. Manrope like the app.
 */

declare module "@mui/material/styles" {
  interface Palette {
    surface: { low: string; main: string; high: string; highest: string };
    gradient: { from: string; to: string; css: string; on: string };
  }
  interface PaletteOptions {
    surface?: Palette["surface"];
    gradient?: Palette["gradient"];
  }
}

const dusk = {
  dark: {
    primary: "#ffb27d",
    onPrimary: "#2e1407",
    secondary: "#8ed8bf",
    tertiary: "#bbb0ff",
    error: "#ff8f8f",
    background: "#0f1117",
    text: "#ecedf3",
    textSecondary: "#9fa3b6",
    surface: { low: "#151720", main: "#1a1c26", high: "#21242f", highest: "#2a2d3a" },
    outline: "#2c2f3c",
    gradient: ["#ffb27d", "#f47c93"],
  },
  light: {
    primary: "#c65a33",
    onPrimary: "#ffffff",
    secondary: "#2c7d66",
    tertiary: "#6352c4",
    error: "#c62f3a",
    background: "#faf7f3",
    text: "#1e1b18",
    textSecondary: "#6a625a",
    surface: { low: "#f5f0ea", main: "#f0eae3", high: "#ebe4dc", highest: "#e4dcd3" },
    outline: "#ddd4ca",
    gradient: ["#c65a33", "#c0466b"],
  },
};

export const FONT = '"Manrope", system-ui, -apple-system, "Segoe UI", Roboto, sans-serif';

export function makeTheme(dark: boolean): Theme {
  const c = dark ? dusk.dark : dusk.light;
  const [from, to] = c.gradient;
  const gradient = `linear-gradient(135deg, ${from}, ${to})`;
  return createTheme({
    palette: {
      mode: dark ? "dark" : "light",
      primary: { main: c.primary, contrastText: c.onPrimary },
      secondary: { main: c.secondary },
      info: { main: c.tertiary },
      error: { main: c.error },
      background: { default: c.background, paper: c.surface.main },
      text: { primary: c.text, secondary: c.textSecondary },
      divider: c.outline,
      surface: c.surface,
      gradient: { from, to, css: gradient, on: c.onPrimary },
    },
    shape: { borderRadius: 14 },
    typography: {
      fontFamily: FONT,
      h4: { fontWeight: 800, letterSpacing: "-0.02em" },
      h5: { fontWeight: 800, letterSpacing: "-0.01em" },
      h6: { fontWeight: 700 },
      subtitle1: { fontWeight: 700 },
      button: { fontWeight: 700, textTransform: "none" },
      overline: { fontWeight: 800, letterSpacing: "0.12em" },
    },
    components: {
      MuiCssBaseline: {
        styleOverrides: {
          body: { backgroundColor: c.background, WebkitFontSmoothing: "antialiased" },
          "::selection": { background: alpha(c.primary, 0.35) },
          "*::-webkit-scrollbar": { width: 10, height: 10 },
          "*::-webkit-scrollbar-thumb": { background: c.surface.highest, borderRadius: 8, border: `2px solid ${c.background}` },
        },
      },
      MuiPaper: { styleOverrides: { root: { backgroundImage: "none" } } },
      MuiCard: {
        defaultProps: { elevation: 0 },
        styleOverrides: { root: { backgroundColor: c.surface.main, borderRadius: 20, border: `1px solid ${c.outline}` } },
      },
      MuiCardContent: { styleOverrides: { root: { padding: 20, "&:last-child": { paddingBottom: 20 } } } },
      MuiButton: {
        defaultProps: { disableElevation: true },
        styleOverrides: {
          root: { borderRadius: 999, paddingInline: 18 },
          sizeLarge: { paddingBlock: 10, paddingInline: 24, fontSize: 16 },
          containedPrimary: {
            background: gradient,
            color: c.onPrimary,
            "&:hover": { background: gradient, filter: "brightness(1.07)" },
            "&.Mui-disabled": { background: c.surface.highest },
          },
          outlined: { borderColor: c.outline, "&:hover": { borderColor: c.primary } },
          text: { "&:hover": { backgroundColor: alpha(c.primary, 0.08) } },
        },
      },
      MuiIconButton: { styleOverrides: { root: { "&:hover": { backgroundColor: alpha(c.text, 0.06) } } } },
      MuiChip: {
        styleOverrides: {
          root: { borderRadius: 999, fontWeight: 600 },
          filled: { backgroundColor: c.surface.high },
          colorPrimary: { background: alpha(c.primary, 0.16), color: c.primary },
        },
      },
      MuiOutlinedInput: {
        styleOverrides: {
          root: {
            borderRadius: 14,
            backgroundColor: c.surface.low,
            "& .MuiOutlinedInput-notchedOutline": { borderColor: c.outline },
          },
        },
      },
      MuiListItemButton: {
        styleOverrides: {
          root: {
            borderRadius: 12,
            "&.Mui-selected": { backgroundColor: alpha(c.primary, 0.12), "&:hover": { backgroundColor: alpha(c.primary, 0.18) } },
          },
        },
      },
      MuiDialog: { styleOverrides: { paper: { borderRadius: 24, backgroundColor: c.surface.main, border: `1px solid ${c.outline}` } } },
      MuiMenu: { styleOverrides: { paper: { borderRadius: 14, backgroundColor: c.surface.high, border: `1px solid ${c.outline}` } } },
      MuiAlert: { styleOverrides: { root: { borderRadius: 14 } } },
      MuiLinearProgress: {
        styleOverrides: {
          root: { borderRadius: 999, backgroundColor: alpha(c.text, 0.1) },
          bar: { borderRadius: 999, background: gradient },
        },
      },
      MuiSlider: {
        styleOverrides: {
          root: { height: 6 },
          track: { border: 0, background: gradient },
          rail: { backgroundColor: alpha(c.text, 0.18) },
          thumb: { width: 16, height: 16, backgroundColor: c.text, "&:hover, &.Mui-focusVisible": { boxShadow: `0 0 0 8px ${alpha(c.primary, 0.16)}` } },
        },
      },
      MuiTabs: { styleOverrides: { indicator: { height: 3, borderRadius: 3, background: gradient } } },
      MuiTab: { styleOverrides: { root: { fontWeight: 700 } } },
      MuiTooltip: { styleOverrides: { tooltip: { backgroundColor: c.surface.highest, color: c.text, fontSize: 12 } } },
    },
  });
}
