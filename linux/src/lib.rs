//! Panaru per Linux — logica indipendente dall'interfaccia (testabile senza display).

pub mod gokapi;
pub mod history;
pub mod queue;
pub mod store;

pub const APP_ID: &str = "app.panaru.Panaru";

/// Dimensione leggibile (unità binarie, come il Finder/Files: KB, MB, GB).
pub fn format_bytes(n: u64) -> String {
    const UNITS: [&str; 5] = ["byte", "KB", "MB", "GB", "TB"];
    if n < 1024 {
        return format!("{n} byte");
    }
    let mut v = n as f64;
    let mut i = 0;
    while v >= 1024.0 && i < UNITS.len() - 1 {
        v /= 1024.0;
        i += 1;
    }
    if v >= 100.0 {
        format!("{v:.0} {}", UNITS[i])
    } else {
        format!("{v:.1} {}", UNITS[i]).replace('.', ",")
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn bytes() {
        assert_eq!(super::format_bytes(512), "512 byte");
        assert_eq!(super::format_bytes(1536), "1,5 KB");
        assert_eq!(super::format_bytes(250 * 1024 * 1024), "250 MB");
    }
}
