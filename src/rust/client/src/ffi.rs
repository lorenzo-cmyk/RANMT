use std::sync::Arc;

use ranmt_shared::Direction;
use tokio::runtime::Runtime;
use tokio::sync::Mutex;
use tokio_util::sync::CancellationToken;

use crate::{ClientConfig, ClientConnectionState, ClientSnapshot, run_client_with_state};

#[derive(uniffi::Enum, Debug, Clone, Copy)]
pub enum FfiDirection {
    Dl,
    Ul,
}

impl From<FfiDirection> for Direction {
    fn from(value: FfiDirection) -> Self {
        match value {
            FfiDirection::Dl => Direction::Dl,
            FfiDirection::Ul => Direction::Ul,
        }
    }
}

#[derive(uniffi::Record, Debug, Clone)]
pub struct FfiClientConfig {
    pub server_addr: String,
    pub server_fqdn: Option<String>,
    pub port: u16,
    pub direction: FfiDirection,
    pub bitrate_bps: u32,
    pub duration: u64,
    pub insecure: bool,
    pub seed: u64,
}

#[derive(uniffi::Enum, Debug, Clone, Copy)]
pub enum FfiConnectionState {
    Connecting,
    Connected,
    Reconnecting,
    Disconnected,
}

impl From<ClientConnectionState> for FfiConnectionState {
    fn from(value: ClientConnectionState) -> Self {
        match value {
            ClientConnectionState::Connecting => FfiConnectionState::Connecting,
            ClientConnectionState::Connected => FfiConnectionState::Connected,
            ClientConnectionState::Reconnecting => FfiConnectionState::Reconnecting,
            ClientConnectionState::Disconnected => FfiConnectionState::Disconnected,
        }
    }
}

#[derive(uniffi::Record, Debug, Clone)]
pub struct FfiQuicStats {
    pub rtt_ms: f64,
    pub rttvar_ms: f64,
    pub tx_bytes: u64,
    pub rx_bytes: u64,
    pub cwnd: u64,
    pub lost_packets: u64,
    pub send_rate_bps: u64,
}

#[derive(uniffi::Record, Debug, Clone)]
pub struct FfiServerStats {
    pub timestamp_ms: u64,
    pub quic_stats: FfiQuicStats,
}

#[derive(uniffi::Record, Debug, Clone)]
pub struct FfiStatsSnapshot {
    pub connection_state: FfiConnectionState,
    pub server_stats: Option<FfiServerStats>,
}

impl From<FfiClientConfig> for ClientConfig {
    fn from(value: FfiClientConfig) -> Self {
        Self {
            server_addr: value.server_addr,
            server_fqdn: value.server_fqdn,
            port: value.port,
            direction: value.direction.into(),
            bitrate_bps: value.bitrate_bps,
            duration: value.duration,
            insecure: value.insecure,
            seed: value.seed,
        }
    }
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum ClientError {
    #[error("invalid config: {0}")]
    InvalidConfig(String),
    #[error("client runtime error: {0}")]
    Runtime(String),
}

#[derive(uniffi::Object)]
pub struct ClientHandle {
    inner: Arc<HandleInner>,
}

struct HandleInner {
    cancel: CancellationToken,
    task: Mutex<Option<tokio::task::JoinHandle<Result<(), String>>>>,
    snapshot: Arc<Mutex<ClientSnapshot>>,
    #[allow(dead_code)] // Keeping `runtime` alive ensures background workers function properly
    runtime: Arc<Runtime>,
}

impl ClientHandle {
    fn new(
        task: tokio::task::JoinHandle<Result<(), String>>,
        cancel: CancellationToken,
        snapshot: Arc<Mutex<ClientSnapshot>>,
        runtime: Arc<Runtime>,
    ) -> Self {
        Self {
            inner: Arc::new(HandleInner {
                cancel,
                task: Mutex::new(Some(task)),
                snapshot,
                runtime,
            }),
        }
    }
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn start_client(config: FfiClientConfig) -> Result<ClientHandle, ClientError> {
    if config.server_addr.trim().is_empty() {
        return Err(ClientError::InvalidConfig(
            "server_addr is empty".to_string(),
        ));
    }
    if config.port == 0 {
        return Err(ClientError::InvalidConfig("port must be > 0".to_string()));
    }

    let runtime = Arc::new(Runtime::new().map_err(|err| ClientError::Runtime(err.to_string()))?);
    let cancel = CancellationToken::new();
    let cancel_task = cancel.clone();
    let config = ClientConfig::from(config);
    let snapshot = Arc::new(Mutex::new(ClientSnapshot {
        connection_state: ClientConnectionState::Connecting,
        last_stats: None,
    }));
    let snapshot_task = Arc::clone(&snapshot);
    let task = runtime.spawn(async move {
        run_client_with_state(config, cancel_task, Some(snapshot_task))
            .await
            .map_err(|err| err.to_string())
    });

    Ok(ClientHandle::new(task, cancel, snapshot, runtime))
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn stop_client(handle: &ClientHandle) -> Result<(), ClientError> {
    handle.inner.cancel.cancel();

    let mut task = handle.inner.task.lock().await;
    if let Some(join) = task.take() {
        match join.await {
            Ok(Ok(())) => Ok(()),
            Ok(Err(err)) => Err(ClientError::Runtime(err)),
            Err(err) => Err(ClientError::Runtime(err.to_string())),
        }
    } else {
        Ok(())
    }
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn get_stats(handle: &ClientHandle) -> Result<FfiStatsSnapshot, ClientError> {
    let snapshot = handle.inner.snapshot.lock().await;
    let server_stats = snapshot.last_stats.as_ref().map(|stats| FfiServerStats {
        timestamp_ms: stats.timestamp_ms,
        quic_stats: FfiQuicStats {
            rtt_ms: stats.quic_stats.rtt_ms,
            rttvar_ms: stats.quic_stats.rttvar_ms,
            tx_bytes: stats.quic_stats.tx_bytes,
            rx_bytes: stats.quic_stats.rx_bytes,
            cwnd: stats.quic_stats.cwnd,
            lost_packets: stats.quic_stats.lost_packets,
            send_rate_bps: stats.quic_stats.send_rate_bps,
        },
    });

    Ok(FfiStatsSnapshot {
        connection_state: snapshot.connection_state.into(),
        server_stats,
    })
}
