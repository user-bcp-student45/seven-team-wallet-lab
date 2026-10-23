# Frontend

MVP demo implementado: Angular 22 (standalone, signals, Reactive Forms, TypeScript strict),
responsive y con la marca/pantallas del PDF de la Billetera Kipu: onboarding de 4 pasos,
Pagar (contacto y monto), revision con destino verificado, resultado exitoso o no realizado
y historial. Consume solo el BFF por el mismo origen (cookie de sesion HttpOnly, XSRF);
no guarda datos en el navegador. El escaneo QR, Enviar y Cobrar quedan como pestanas
deshabilitadas (HU-03 diferida).

```powershell
npm ci ; npm start     # proxy a http://localhost:8080 (proxy.conf.json)
npm test -- --watch=false ; npm run build
```

En el reintento tras un corte de red se conserva la clave de idempotencia del intento y se
consulta el resultado original; un reintento tras un resultado no exitoso crea un intento nuevo.
Las pruebas Playwright y la verificacion de accesibilidad WCAG siguen pendientes.

Notas previas de diseno:

Secuencia activa: HU-01 -> HU-02 -> HU-04.
Comercio, soporte/disputas y conciliacion operativa quedan diferidos. No se
requiere app nativa en esta fase. Build Node/Angular y servidor web se ejecutaran
en contenedores OCI/Compose compatibles con Docker y Podman Desktop, sin Node host.

## Areas funcionales

| Area | HU | Vistas por definir |
| --- | --- | --- |
| Onboarding | HU-01 | Registro, login, pasos, validacion de identidad interna y activacion |
| Pagos entre contactos | HU-02 | Contacto/monto, confirmacion, resultado e historial |
| Idempotencia | HU-04 | Reintento seguro, referencia original y ausencia de doble cobro |
| Busqueda de movimientos/conceptos | Extension HU-02 CA3 | Busqueda, filtros y paginacion por aprobar en Q-17 |

Router por capacidades y roles, Reactive Forms y contratos OpenAPI. La UI no
es una frontera de autorizacion; el servidor valida propiedad, permisos y monto.
Integracion con BFF mediante sesion protegida, sin tokens en localStorage.

Disenar estados de carga, validacion, error, reintento con la misma clave,
expiracion segura y
recuperacion, asi como accesibilidad por teclado y lectores. No mostrar exito
por un HTTP 202 ni habilitar otro pago mientras el anterior pueda ejecutarse.
Proponer WCAG 2.2 AA como objetivo de verificacion, no conformidad ya obtenida.

## Antes de construir

El cliente consulta al BFF, nunca directamente a Meilisearch. Mostrar de forma
distinta busqueda vacia, indice atrasado y servicio no disponible. La confirmacion
por referencia consulta la autoridad PostgreSQL; no deducir pago fallido por
ausencia de un resultado de busqueda. No mostrar snippets o conteos ajenos.

Definir wireframes y sistema visual con los agentes de diseno, confirmar estados
y contratos, fijar Angular/Node LTS compatibles y lockfile. Luego documentar
comandos reales y pruebas TestBed/Playwright responsive. No instalar un framework
UI ni generar pantallas antes de aprobar ese diseno.