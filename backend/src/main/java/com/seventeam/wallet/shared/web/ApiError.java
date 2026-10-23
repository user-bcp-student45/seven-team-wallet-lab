package com.seventeam.wallet.shared.web;

import com.seventeam.wallet.shared.WalletException.FieldIssue;
import java.util.List;

public record ApiError(int status, String code, String message, List<FieldIssue> fieldErrors, String requestId) {}
