# Orquestacion de infraestructura local

## MVP demo (Kipu)

[compose.yaml](compose.yaml) levanta PostgreSQL, backend Spring Boot y frontend Angular
(nginx, mismo origen) para HU-01, HU-02 y HU-04. Meilisearch y observabilidad siguen
pendientes (ver servicios previstos).

```powershell
Copy-Item .env.example .env   # completar WALLET_DB_PASSWORD y WALLET_DEMO_PASSWORD (datos ficticios)
podman compose up -d --build  # o: docker compose up -d --build
# Web: http://127.0.0.1:8081   Parar: podman compose down   (con -v borra los datos)
```

Usuarios demo sembrados (clave = `WALLET_DEMO_PASSWORD`): `maria.fernandez@demo.kipu.example`
(pagador, saldo S/ 200.00), y los contactos `carlos.quispe@`, `ana.torres@` y
`luis.mendoza@` con el mismo dominio. Un usuario nuevo recibe S/ 100.00 ficticios al activarse.
Solo en demo, el codigo de verificacion del celular se muestra en pantalla (no hay SMS).
La cookie de sesion no es `Secure` en HTTP local; falta el perfil HTTPS.

## Estado del resto

Los demas servicios siguen pendientes. El runner tiene [Compose ejecutable](../agents/runner/compose.yaml)
separado y un [controlador externo](Invoke-WalletFlow.ps1) para Windows/Podman.
Ver [operacion, gates y limites](../agents/runner/README.md). Los agentes nunca
reciben el socket del motor; el operador ejecuta los checks aprobados sin red,
con volumen de solo lectura y limites de recursos/tiempo.

## Servicios previstos

| Servicio | Proposito |
| --- | --- |
| PostgreSQL | Base wallet: usuarios con clave hasheada, contactos, ledger, idempotencia y outbox |
| Meilisearch | Read model reconstruible para movimientos y conceptos; red interna y volumen propio |
| Backend | BFF/API Java 21 con usuarios y claves hasheadas en PostgreSQL, workers outbox y proyeccion Meilisearch; sin job diario HU-06 |
| Frontend | Angular, mismo origen mediante proxy cuando aplique |
| OTel Collector | Recepcion OTLP, redaccion y exportacion |
| Prometheus, Tempo, Loki, Grafana | Metricas, trazas, logs y dashboards |
| CrewAI / herramientas | Runner disponible; modelo/checks reales requieren configuracion y aprobacion |

HU activas, en orden: HU-01 -> HU-02 -> HU-04. Las otras historias no habilitan
servicios o modulos adicionales. Todo componente ejecutable, incluidos build y
pruebas, se empaqueta en contenedores; no se exige Java/Node/Python en el host.

## Podman Desktop en Windows

Requisitos del futuro arranque: CLI `podman`, una maquina/conexion Podman operativa
y un proveedor Compose compatible. Podman Desktop instalado no garantiza por si
solo que `podman compose` tenga proveedor. Comprobar version, conexion y proveedor
antes de ejecutar; no instalar Docker Desktop ni modificar la maquina sin permiso.

`podman --version` y `podman compose version` son comprobaciones de herramientas,
no comandos de arranque del producto. Cuando exista Compose, documentar comandos
equivalentes de `podman compose` y `docker compose`, con ruta explicita al archivo.
No son motores que deban ejecutarse simultaneamente.

Comprobado el 2026-10-23: CLI Podman 6.0.2, proveedor externo podman-compose
1.6.0 y maquina Linux amd64 operativa. Build del runner, compose config e
init/start/status pasaron sin modelos. El proveedor usa Python en el host;
esto es tooling de Compose, no un runtime de las aplicaciones o de CrewAI.
La compatibilidad del futuro Compose completo sigue pendiente de prueba real.

## Requisitos del futuro Compose

Perfiles para aplicacion, observabilidad y herramientas; el arranque completo
incluira todos los servicios del producto y los checks necesarios. CrewAI se
activa solo con permiso especifico. Healthchecks, volumenes claramente
identificados y seeds sinteticos reproducibles. Puertos locales acotados a
loopback cuando sea posible, redes internas y credenciales de laboratorio via
variables no versionadas. Imagenes fijadas, recursos limitados y usuarios sin
privilegios donde se soporte. Nunca montar socket Docker/Podman en agentes.
Imagenes multistage para Angular/Java y una imagen de herramientas fijada para
CrewAI. No depender de extensiones exclusivas de Docker: verificar soporte real
de healthchecks, dependencias y permisos de volumen en el proveedor Compose local.

TLS/sesion segura del BFF requieren un perfil HTTPS local documentado o una
excepcion de desarrollo explicita; no desactivar protecciones silenciosamente.
No exponer PostgreSQL, Meilisearch o dashboards a internet.
Separar credenciales admin/indexacion/busqueda y no entregarlas al frontend.

## Evidencia requerida al implementarlo

Validar config, build y healthchecks con `podman compose` y comprobar compatibilidad
de la misma definicion con `docker compose` cuando este disponible. Arranque
reproducible desde imagenes/volumenes documentados; comprobar
traza API -> worker, ausencia de secretos/PII y escenarios de pago duplicado,
reintento con la misma clave y pagos concurrentes contra el propio backend. Probar Meilisearch caido/atrasado, indexacion
recuperable, aislamiento por usuario, busqueda por concepto y reconstruccion con
pagos concurrentes sin tocar el ledger. Documentar arranque/parada,
backup/restore y limpieza de volumenes con advertencia de perdida de datos.

El arranque del runner esta en su manual; el del producto completo sigue pendiente.