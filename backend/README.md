# Backend

MVP demo implementado: Java 21, Spring Boot 3.5 (MVC imperativo), Maven, PostgreSQL y Flyway.
Contrato OpenAPI propio en [swagger.json](swagger.json). Cubre HU-01 (onboarding de 4 pasos),
HU-02 (intento, revision y confirmacion de pago entre contactos) y HU-04 (idempotencia).
Meilisearch, workers del outbox, OTel y conciliacion diaria siguen pendientes.

```powershell
# Pruebas (21) contra PostgreSQL real; la base wallet_test la crea orchestration/postgres/init
$env:WALLET_DB_PASSWORD = '<clave de orchestration/.env>'
mvn test
```

Supuestos de MVP configurables en [application.yml](src/main/resources/application.yml) y
pendientes de decision: monto maximo S/ 500.00 (Q-04), expiracion del intento 60 s (Q-05),
retencion de claves sin definir (Q-07), fondeo ficticio inicial de S/ 100.00 (Q-10) y bloqueo
tras 5 intentos fallidos (Q-11). Los contactos de un usuario activado son los usuarios demo.

Limites arquitectonicos previstos (arquitectura objetivo). Alcance y secuencia:
HU-01 onboarding -> HU-02 confirmacion de pago -> HU-04 idempotencia. Ver
[stack y ADR](../architecture/README.md).

## Organizacion propuesta

Modulos activos: identity-onboarding, wallet-ledger, payments, movement-search
y analytics. Merchant, reconciliation y support se difieren con HU-03/HU-05/HU-06.
Cada modulo separa domain, application, adapters/in y
adapters/out; wiring Spring en el borde. Un unico backend contiene API/BFF,
workers outbox y proyeccion de busqueda, sin extraer microservicios ni
implementar conciliacion diaria. HU-04 CA4 conserva evidencia original/reintento.

Dominio puro: Money, Currency, PaymentId, estado e invariantes; ledger sin tipos
JPA/Spring. Aplicacion: commands y queries, puertos, autorizacion de caso de uso
y fronteras transaccionales. Adaptadores: REST, SQL y Meilisearch. No hay
proveedores de pago/KYC ni IdP: el pago es una transferencia entre contactos
dentro de una transaccion local y los usuarios viven en una tabla de PostgreSQL
con clave hasheada (Argon2id, Spring Security) y sesion de servidor. PostgreSQL mantiene comandos, resultado, ledger e idempotencia;
Meilisearch busca movimientos/conceptos, sin determinar saldos ni estados finales.

Indexar mediante outbox confirmado con el cambio SQL; worker idempotente con
orden/version por movimiento y confirmacion de tarea asincrona antes del
checkpoint. Reindexar desde PostgreSQL sin replay financiero. El BFF aplica
ownership y verificacion actual SQL; no expone el indice ni sus claves al cliente.
Ver [ADR-0007](../architecture/adr/0007-cqrs-postgresql-meilisearch.md).

## Condiciones antes del esqueleto

- Fijar version Spring Boot compatible con Java 21 y Maven Wrapper en Q-16.
- Aprobar limites modulares, contratos, estados e invariantes en OpenSpec.
- Preparar PostgreSQL/Flyway y pruebas Testcontainers, no H2 como sustituto
  de las garantias transaccionales a comprobar.
- Definir comandos reales de build, test y ArchUnit al generar el proyecto.
- Incluir imagen multistage Java 21/Maven y runtime en Compose OCI compatible
  con Docker/Podman Desktop; sin Java/Maven host. Ejecutar checks en contenedores.
- Probar PostgreSQL y Meilisearch reales: ownership, busqueda por concepto,
  retraso/caida del indice, duplicados y reindex concurrente con pagos. Validar
  Testcontainers/Podman en un harness de pruebas confiable separado del agente;
  no resolver conectividad montando el socket del motor en el runner CrewAI.
- No implementar fondeo real ni almacenar datos bancarios reales.

La estructura de paquetes definitiva y el esquema de tablas se decidiran con
los agentes de diseno despues de refinar las HU; este directorio no simula codigo.