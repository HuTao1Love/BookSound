import { CssBaseline, ThemeProvider, createTheme, useMediaQuery } from "@mui/material";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { StrictMode, useMemo } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import App from "./App";
import "./i18n";

const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 30_000, refetchOnWindowFocus: true, retry: 1 } },
});

function Root() {
  const dark = useMediaQuery("(prefers-color-scheme: dark)");
  const theme = useMemo(
    () =>
      createTheme({
        palette: {
          mode: dark ? "dark" : "light",
          primary: { main: dark ? "#d0bcff" : "#6750a4" },
          secondary: { main: dark ? "#ccc2dc" : "#625b71" },
          background: dark ? { default: "#141218", paper: "#1d1b20" } : { default: "#fef7ff", paper: "#ffffff" },
        },
        shape: { borderRadius: 12 },
        typography: { fontFamily: 'system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif' },
        components: {
          MuiButton: { styleOverrides: { root: { textTransform: "none", borderRadius: 20 } } },
          MuiTab: { styleOverrides: { root: { textTransform: "none" } } },
        },
      }),
    [dark],
  );
  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </QueryClientProvider>
    </ThemeProvider>
  );
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <Root />
  </StrictMode>,
);
