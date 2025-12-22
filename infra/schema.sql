CREATE TABLE BuildSessions (
    UserId STRING(MAX) NOT NULL,
    RepoHash STRING(MAX) NOT NULL, -- MD5 of the local path
    SessionId STRING(MAX),         -- ID to handle desktop bounces
    PodIP STRING(MAX),
    Status STRING(MAX)             -- PENDING, READY
) PRIMARY KEY (UserId, RepoHash);
