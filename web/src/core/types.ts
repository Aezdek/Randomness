// Wire types for /api/v1, mirroring shared/protocol/.../Api.kt. Binary fields are base64url without padding.

export const MAX_MEMBERS = 10;

export interface DeviceInfo { name: string; platform: 'web' | 'android' }
export interface PreloginResponse { salt: string; opsLimit: number; memLimit: number }
export interface SignupRequest {
  inviteCode: string; username: string; salt: string; opsLimit: number; memLimit: number; authKey: string;
  signPk: string; boxPk: string; boxPkSig: string; vault: string; device: DeviceInfo;
}
export interface Me { id: string; username: string; isAdmin: boolean; isPrivate: boolean; showActivity: boolean; createdAt: number; keyVersion: number; readReceipts?: boolean }
export interface AuthResponse { token: string; me: Me; vault: string }
export interface SessionDto { id: string; deviceName: string; platform: string; createdAt: number; lastSeenAt: number; current: boolean }

export type FollowStateValue = 'none' | 'requested' | 'following';

export interface UserDto {
  id: string; username: string; signPk: string; boxPk: string; boxPkSig: string; keyVersion: number; isPrivate: boolean;
  createdAt: number; followState: FollowStateValue; followsMe: FollowStateValue; blockedByMe: boolean;
  lastActiveAt?: number | null; online?: boolean; deleted?: boolean;
}

export interface WrapDto { recipientId: string; sealed: string }

export interface ObjectUpload {
  id: string; kind: string; parentId?: string | null; threadId?: string | null; audience: string; createdAt: number;
  expiresAt?: number | null; version: number; blobIds: string[]; refIds: string[]; payload: string; commitment: string;
  signature: string; wraps: WrapDto[];
}

export interface ObjectDto {
  id: string; kind: string; authorId: string; parentId?: string | null; threadId?: string | null; audience: string;
  createdAt: number; expiresAt?: number | null; version: number; blobIds: string[]; refIds: string[]; payload: string;
  commitment: string; signature: string; wrap?: string | null; archived: boolean; receivedAt: number;
}

export interface WrapUpload { objectId: string; recipientId: string; sealed: string }
export interface ViewDto { userId: string; at: number }
export interface WrapTaskDto { id: number; recipientId: string; reason: string; objects: ObjectDto[] }

export interface ThreadMemberDto { userId: string; role: string; joinedAt: number; lastReadAt: number }
export interface ThreadDto { id: string; isGroup: boolean; createdBy: string; createdAt: number; members: ThreadMemberDto[] }

export interface SyncResponse {
  cursor: number; hasMore: boolean; objects: ObjectDto[]; deletedIds: string[]; users: UserDto[]; threads: ThreadDto[];
  removedThreadIds: string[]; pendingWrapTasks: number; followRequests: string[];
}

export interface UploadState { uploadId: string; size: number; received: number; chunkSize: number }
export interface BlobDto { blobId: string; size: number }

export interface SignalDto { sealed: string; signature: string; sentAt: number }
export interface WsFrame {
  t: string; cursor?: number; threadId?: string; userId?: string; from?: string; to?: string; online?: boolean;
  signal?: SignalDto; ring?: boolean; callId?: string;
}

export interface IceServer { urls: string[]; username?: string; credential?: string }
export interface TurnResponse { iceServers: IceServer[]; relay: boolean }
export interface InviteDto { code: string; createdAt: number; expiresAt: number; usedBy?: string | null }
export interface AdminStatus {
  users: number; maxUsers: number; diskFreeBytes: number; dataBytes: number; uploadsBlocked: boolean;
  lastBackupAt?: number | null; lastBackupOk?: boolean | null; backupMessage?: string | null; version: string;
  commit?: string | null; startedAt?: number; latestVersion?: string | null; updateAvailable?: boolean;
  /** How long a new invite lasts, from the server's settings. */
  inviteDays?: number;
}
/** The server's own settings (data/config.json), changed from the admin panel with the server password. */
export interface ServerSettingsDto {
  maxMembers: number; inviteDays: number; minFreeGb: number; webAdmin: boolean;
}
export type ServerActionId = 'update' | 'restart' | 'backup' | 'check';
/** The last job the server's control helper ran or is running. */
export interface ControlStatusDto {
  id: string; action: ServerActionId | string; state: 'queued' | 'running' | 'done' | 'failed';
  requestedAt?: number | null; startedAt?: number | null; finishedAt?: number | null; message?: string | null;
}
/** The admin panel's Server tab (see AdminServerDto in Api.kt). */
export interface AdminServerDto {
  settings: ServerSettingsDto; version: string; commit?: string | null; latestVersion?: string | null; updateAvailable?: boolean;
  agent: boolean; control?: ControlStatusDto | null; log?: string | null; unlockedUntil?: number | null;
  /** Where Hush is installed on the server, for the commands the panel shows. */
  folder?: string;
}
/** The bounds the server accepts (ServerLimits in Api.kt). */
export const SERVER_LIMITS = { members: [2, 50], inviteDays: [1, 30], minFreeGb: [1, 500] } as const;
export interface AdminUserDto {
  id: string; username: string; isAdmin: boolean; createdAt: number; lastActiveAt?: number | null;
  storageBytes: number; posts: number; sessions: number;
  /** 'active', 'restricted' (blocked: can read, can't post or message) or 'disabled' (can't sign in). */
  status?: 'active' | 'restricted' | 'disabled';
}
/** What the server knows about a member, for the admin: metadata only (see AdminMemberDto in Api.kt). */
export interface AdminMemberDto {
  user: AdminUserDto; isPrivate: boolean; showActivity: boolean; invitedBy?: string | null; invitesCreated: number;
  devices: { name: string; platform: string; createdAt: number; lastSeenAt: number }[];
  followers: string[]; following: string[]; pendingRequests: number; blocking: number;
  chats: { id: string; isGroup: boolean; members: string[]; createdAt: number; joinedAt: number; messages: number; sentByThem: number; lastMessageAt?: number | null }[];
  counts: Record<string, number>; lastPostAt?: number | null;
}
export interface AppVersion { versionCode: number; versionName: string; sha256: string; size: number; url: string }
export interface IdList { ids: string[] }
