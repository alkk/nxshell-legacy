# nxshell-legacy

Extract of the Jython-based `nxshell` that was dropped in NetXMS 7.0. It is unsupported and kept for my personal use. For new scripts, use the NetXMS WebAPI.

## Build

```sh
mvn package
```

Produces a single fat jar: `target/nxshell-<version>.jar`.

## Run

```sh
java -jar nxshell-7.0.4.jar             # interactive shell
java -jar nxshell-7.0.4.jar script.py   # run a script
```

The connected session is available as `session` (and `s`). Settings are taken from `nxshell.properties` (current directory or classpath), then from the file given by `-Dnxshell.properties=<file>`, then from `-D` system properties; later sources win:

| Property | Default |
|---|---|
| `netxms.server` | `127.0.0.1` (accepts `host:port`) |
| `netxms.port` | `4701` |
| `netxms.login` | `admin` |
| `netxms.password` | empty |
| `netxms.token` | — (used instead of login/password) |
| `netxms.enableCompression` | `true` |
| `netxms.syncObjects` | `true` |
| `nxshell.interactive` | force interactive prompts |
| `nxshell.debug` | debug logging (`-D` only) |
