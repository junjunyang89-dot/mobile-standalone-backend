package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DebugFlowServiceTest {

    private final DebugFlowService svc = new DebugFlowService();

    @Test
    void fullChain_extractsNestedFieldsAcrossLayers() {
        svc.reset();

        Map<String, Object> session = svc.openSession(reqActor("flow-tester"));
        @SuppressWarnings("unchecked")
        Map<String, Object> auth = (Map<String, Object>) ((Map<?, ?>) session.get("result")).get("auth");
        @SuppressWarnings("unchecked")
        Map<String, Object> sess = (Map<String, Object>) auth.get("session");
        @SuppressWarnings("unchecked")
        Map<String, Object> account = (Map<String, Object>) auth.get("account");
        String token = String.valueOf(sess.get("token"));
        String accountId = String.valueOf(account.get("id"));

        Map<String, Object> catalog = svc.catalog(accountId, token);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) catalog.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> cat = (Map<String, Object>) data.get("catalog");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) cat.get("groups");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> products = (List<Map<String, Object>>) groups.get(0).get("products");
        Map<String, Object> p0 = products.get(0);
        assertEquals("P-2001", p0.get("id"));
        assertEquals("NEST-SKU-01", p0.get("sku"));

        DebugFlowService.AddCartRequest cartReq = new DebugFlowService.AddCartRequest();
        cartReq.setSessionToken(token);
        cartReq.setProductId("P-2001");
        cartReq.setSku("NEST-SKU-01");
        cartReq.setQty(2);
        cartReq.setUnitPrice(39.9);
        Map<String, Object> cartResp = svc.addToCart(cartReq);
        @SuppressWarnings("unchecked")
        Map<String, Object> cart = (Map<String, Object>) ((Map<?, ?>) cartResp.get("result")).get("cart");
        String cartId = String.valueOf(cart.get("id"));

        DebugFlowService.CheckoutRequest checkoutReq = new DebugFlowService.CheckoutRequest();
        checkoutReq.setSessionToken(token);
        checkoutReq.setCartId(cartId);
        Map<String, Object> checkout = svc.checkout(checkoutReq);
        @SuppressWarnings("unchecked")
        Map<String, Object> order = (Map<String, Object>) ((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) checkout.get("data")).get("checkout")).get("order"));
        String orderId = String.valueOf(order.get("id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payment = (Map<String, Object>) order.get("payment");
        String payToken = String.valueOf(payment.get("token"));

        DebugFlowService.PayRequest payReq = new DebugFlowService.PayRequest();
        payReq.setSessionToken(token);
        payReq.setOrderId(orderId);
        payReq.setPayToken(payToken);
        Map<String, Object> pay = svc.pay(payReq);
        @SuppressWarnings("unchecked")
        Map<String, Object> receipt = (Map<String, Object>) ((Map<?, ?>) ((Map<?, ?>) pay.get("result")).get("payment")).get("receipt");
        @SuppressWarnings("unchecked")
        Map<String, Object> txn = (Map<String, Object>) receipt.get("txn");
        String txnId = String.valueOf(txn.get("id"));

        Map<String, Object> ticket = svc.ticket(orderId, txnId, token);
        @SuppressWarnings("unchecked")
        Map<String, Object> verify = (Map<String, Object>) ((Map<?, ?>) ((Map<?, ?>) ticket.get("ticket")).get("body")).get("verify");
        assertEquals(true, verify.get("allPassed"));
        assertEquals(6, verify.get("layers"));
    }

    private static DebugFlowService.OpenSessionRequest reqActor(String actor) {
        DebugFlowService.OpenSessionRequest r = new DebugFlowService.OpenSessionRequest();
        r.setActor(actor);
        return r;
    }
}
