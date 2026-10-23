package com.seventeam.wallet.shared;

import java.io.Serializable;
import java.util.UUID;

/** Identidad autenticada; el servidor nunca toma userId del cliente. */
public record AuthenticatedUser(UUID userId, String email) implements Serializable {}
