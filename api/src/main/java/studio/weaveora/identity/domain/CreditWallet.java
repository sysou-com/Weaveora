package studio.weaveora.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 额度钱包（§14 credit_wallets 表）——按工作区计费（§7.1，禁止 User 上的三级配额字段）。 */
@Entity
@Table(name = "credit_wallets")
public class CreditWallet {

    @Id
    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal frozen = BigDecimal.ZERO;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected CreditWallet() {
    }

    public static CreditWallet create(UUID workspaceId, BigDecimal initialBalance) {
        CreditWallet w = new CreditWallet();
        w.workspaceId = workspaceId;
        w.balance = initialBalance;
        w.frozen = BigDecimal.ZERO;
        return w;
    }

    public UUID workspaceId() { return workspaceId; }
    public BigDecimal balance() { return balance; }
    public BigDecimal frozen() { return frozen; }
}
