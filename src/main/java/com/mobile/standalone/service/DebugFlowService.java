package com.mobile.standalone.service;

import lombok.Data;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 独立调试链路：多层依赖 + 嵌套 JSON 抽取。
 * <p>
 * 内存态，重启清空。不落库，专门给接口自动化练 extract / 变量传递。
 */
@Service
public class DebugFlowService {

    private final AtomicLong seq = new AtomicLong(1000);
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Cart> carts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Order> orders = new ConcurrentHashMap<>();

    /** 模拟开会话：返回深层 sessionToken / accountId */
    public Map<String, Object> openSession(OpenSessionRequest req) {
        String actor = req == null || blank(req.getActor()) ? "debug-actor" : req.getActor().trim();
        String sessionId = "SES-" + seq.incrementAndGet();
        String accountId = "ACC-" + (100 + seq.get() % 50);
        String sessionToken = "stk-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        Session s = new Session();
        s.sessionId = sessionId;
        s.accountId = accountId;
        s.sessionToken = sessionToken;
        s.actor = actor;
        sessions.put(sessionId, s);
        sessions.put(sessionToken, s);

        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("session", Map.of(
                "id", sessionId,
                "token", sessionToken,
                "ttlSec", 3600
        ));
        auth.put("account", Map.of(
                "id", accountId,
                "actor", actor,
                "profile", Map.of("tier", "GOLD", "region", "cn-east")
        ));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("code", 0);
        root.put("message", "session opened");
        root.put("result", Map.of("auth", auth));
        root.put("meta", Map.of("traceId", "tr-flow-" + sessionId));
        return root;
    }

    /** 商品目录：嵌套列表，抽 productId / sku / unitPrice */
    public Map<String, Object> catalog(String accountId, String sessionToken) {
        requireSession(sessionToken);
        if (blank(accountId)) {
            throw new IllegalArgumentException("accountId 不能为空");
        }

        Map<String, Object> p0 = product("P-2001", "NEST-SKU-01", 39.9, "多层依赖商品A");
        Map<String, Object> p1 = product("P-2002", "NEST-SKU-02", 59.0, "多层依赖商品B");

        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("accountId", accountId);
        catalog.put("currency", "CNY");
        catalog.put("groups", List.of(
                Map.of(
                        "name", "推荐",
                        "products", List.of(p0, p1)
                )
        ));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("code", 0);
        root.put("data", Map.of("catalog", catalog));
        root.put("meta", Map.of("page", Map.of("pageNo", 1, "pageSize", 20, "total", 2)));
        return root;
    }

    /** 加购：依赖 productId/sku，返回 cartId / lineId */
    public Map<String, Object> addToCart(AddCartRequest req) {
        Session s = requireSession(req == null ? null : req.getSessionToken());
        if (req == null || blank(req.getProductId()) || blank(req.getSku())) {
            throw new IllegalArgumentException("productId / sku 不能为空");
        }
        int qty = req.getQty() == null || req.getQty() < 1 ? 1 : req.getQty();
        double unitPrice = req.getUnitPrice() == null ? 39.9 : req.getUnitPrice();

        String cartId = "CART-" + seq.incrementAndGet();
        String lineId = "LINE-" + seq.incrementAndGet();

        Cart cart = new Cart();
        cart.cartId = cartId;
        cart.sessionId = s.sessionId;
        cart.accountId = s.accountId;
        cart.productId = req.getProductId().trim();
        cart.sku = req.getSku().trim();
        cart.lineId = lineId;
        cart.qty = qty;
        cart.unitPrice = unitPrice;
        carts.put(cartId, cart);

        Map<String, Object> line = new LinkedHashMap<>();
        line.put("lineId", lineId);
        line.put("product", Map.of(
                "id", cart.productId,
                "sku", cart.sku,
                "pricing", Map.of("unitPrice", unitPrice, "currency", "CNY")
        ));
        line.put("qty", qty);
        line.put("subtotal", round2(unitPrice * qty));

        Map<String, Object> cartNode = new LinkedHashMap<>();
        cartNode.put("id", cartId);
        cartNode.put("accountId", s.accountId);
        cartNode.put("lines", List.of(line));
        cartNode.put("summary", Map.of("lineCount", 1, "amount", round2(unitPrice * qty)));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("code", 0);
        root.put("result", Map.of("cart", cartNode));
        return root;
    }

    /** 下单：依赖 cartId + sessionToken，返回 orderId / payToken */
    public Map<String, Object> checkout(CheckoutRequest req) {
        Session s = requireSession(req == null ? null : req.getSessionToken());
        if (req == null || blank(req.getCartId())) {
            throw new IllegalArgumentException("cartId 不能为空");
        }
        Cart cart = carts.get(req.getCartId().trim());
        if (cart == null) {
            throw new IllegalArgumentException("购物车不存在: " + req.getCartId());
        }
        if (!Objects.equals(cart.sessionId, s.sessionId)) {
            throw new IllegalArgumentException("购物车与会话不匹配");
        }

        String orderId = "ORD-" + seq.incrementAndGet();
        String payToken = "pay-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        double amount = round2(cart.unitPrice * cart.qty);

        Order order = new Order();
        order.orderId = orderId;
        order.cartId = cart.cartId;
        order.sessionId = s.sessionId;
        order.accountId = s.accountId;
        order.productId = cart.productId;
        order.sku = cart.sku;
        order.amount = amount;
        order.payToken = payToken;
        order.status = "PENDING_PAY";
        orders.put(orderId, order);

        Map<String, Object> pay = new LinkedHashMap<>();
        pay.put("token", payToken);
        pay.put("channel", "mock-pay");
        pay.put("expiresIn", 600);

        Map<String, Object> orderNode = new LinkedHashMap<>();
        orderNode.put("id", orderId);
        orderNode.put("status", order.status);
        orderNode.put("amount", Map.of("value", amount, "currency", "CNY"));
        orderNode.put("items", List.of(Map.of(
                "sku", cart.sku,
                "productId", cart.productId,
                "qty", cart.qty
        )));
        orderNode.put("payment", pay);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("code", 0);
        root.put("data", Map.of(
                "checkout", Map.of(
                        "order", orderNode,
                        "fromCart", cart.cartId
                )
        ));
        return root;
    }

    /** 支付：依赖 orderId + payToken，返回 txnId / receiptNo */
    public Map<String, Object> pay(PayRequest req) {
        if (req == null || blank(req.getOrderId()) || blank(req.getPayToken())) {
            throw new IllegalArgumentException("orderId / payToken 不能为空");
        }
        requireSession(req.getSessionToken());
        Order order = orders.get(req.getOrderId().trim());
        if (order == null) {
            throw new IllegalArgumentException("订单不存在: " + req.getOrderId());
        }
        if (!Objects.equals(order.payToken, req.getPayToken().trim())) {
            throw new IllegalArgumentException("payToken 不匹配");
        }
        if ("PAID".equals(order.status)) {
            // 幂等：重复支付返回同一收据
            return payResponse(order);
        }

        order.status = "PAID";
        order.txnId = "TXN-" + seq.incrementAndGet();
        order.receiptNo = "RCP-" + seq.incrementAndGet();
        return payResponse(order);
    }

    /** 终态票据：query 带 txnId，嵌套校验整条链路 */
    public Map<String, Object> ticket(String orderId, String txnId, String sessionToken) {
        requireSession(sessionToken);
        if (blank(orderId)) {
            throw new IllegalArgumentException("orderId 不能为空");
        }
        Order order = orders.get(orderId.trim());
        if (order == null) {
            throw new IllegalArgumentException("订单不存在: " + orderId);
        }
        boolean txnOk = blank(txnId) || Objects.equals(txnId.trim(), order.txnId);
        boolean paid = "PAID".equals(order.status);

        Map<String, Object> chain = new LinkedHashMap<>();
        chain.put("accountId", order.accountId);
        chain.put("cartId", order.cartId);
        chain.put("orderId", order.orderId);
        chain.put("sku", order.sku);
        chain.put("txnId", order.txnId);
        chain.put("receiptNo", order.receiptNo);

        Map<String, Object> verify = new LinkedHashMap<>();
        verify.put("paid", paid);
        verify.put("txnMatched", txnOk);
        verify.put("allPassed", paid && txnOk);
        verify.put("layers", 6);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("code", 0);
        root.put("ticket", Map.of(
                "header", Map.of("orderId", order.orderId, "status", order.status),
                "body", Map.of(
                        "chain", chain,
                        "verify", verify,
                        "print", Map.of(
                                "title", "debug-flow receipt",
                                "lines", List.of(
                                        Map.of("k", "sku", "v", order.sku),
                                        Map.of("k", "amount", "v", String.valueOf(order.amount)),
                                        Map.of("k", "txn", "v", order.txnId == null ? "" : order.txnId)
                                )
                        )
                )
        ));
        return root;
    }

    public Map<String, Object> reset() {
        sessions.clear();
        carts.clear();
        orders.clear();
        return Map.of("ok", true, "message", "debug-flow 内存态已清空");
    }

    private Map<String, Object> payResponse(Order order) {
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("no", order.receiptNo);
        receipt.put("txn", Map.of(
                "id", order.txnId,
                "status", "SUCCESS",
                "paidAmount", Map.of("value", order.amount, "currency", "CNY")
        ));
        receipt.put("orderRef", Map.of("orderId", order.orderId, "sku", order.sku));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("code", 0);
        root.put("result", Map.of(
                "payment", Map.of(
                        "status", "PAID",
                        "receipt", receipt
                )
        ));
        return root;
    }

    private Session requireSession(String sessionToken) {
        if (blank(sessionToken)) {
            throw new IllegalArgumentException("sessionToken 不能为空");
        }
        Session s = sessions.get(sessionToken.trim());
        if (s == null) {
            throw new IllegalArgumentException("sessionToken 无效或已失效");
        }
        return s;
    }

    private static Map<String, Object> product(String id, String sku, double price, String title) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", id);
        p.put("sku", sku);
        p.put("title", title);
        p.put("offer", Map.of(
                "price", Map.of("unit", price, "currency", "CNY"),
                "stock", Map.of("available", true, "qty", 99)
        ));
        return p;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static final class Session {
        String sessionId;
        String accountId;
        String sessionToken;
        String actor;
    }

    private static final class Cart {
        String cartId;
        String sessionId;
        String accountId;
        String productId;
        String sku;
        String lineId;
        int qty;
        double unitPrice;
    }

    private static final class Order {
        String orderId;
        String cartId;
        String sessionId;
        String accountId;
        String productId;
        String sku;
        double amount;
        String payToken;
        String status;
        String txnId;
        String receiptNo;
    }

    @Data
    public static class OpenSessionRequest {
        private String actor;
    }

    @Data
    public static class AddCartRequest {
        private String sessionToken;
        private String productId;
        private String sku;
        private Integer qty;
        private Double unitPrice;
    }

    @Data
    public static class CheckoutRequest {
        private String sessionToken;
        private String cartId;
    }

    @Data
    public static class PayRequest {
        private String sessionToken;
        private String orderId;
        private String payToken;
    }
}
