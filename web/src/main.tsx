import { CssBaseline, ThemeProvider, useMediaQuery } from "@mui/material";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { StrictMode, useMemo } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import App from "./App";
import "./i18n";
import { makeTheme } from "./theme";

const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 30_000, refetchOnWindowFocus: true, retry: 1 } },
});

function Root() {
  const dark = useMediaQuery("(prefers-color-scheme: dark)");
  const theme = useMemo(() => makeTheme(dark), [dark]);
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
