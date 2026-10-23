package com.seventeam.wallet.identity.adapter.out;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ContactRepository {

    public record ContactRow(UUID id, String fullName, String phone) {}

    private final JdbcClient jdbc;

    public ContactRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Contactos activos del propietario. El MVP no define alta de contactos (fuera de alcance). */
    public List<ContactRow> listActive(UUID ownerId) {
        return jdbc.sql("""
                select u.id, u.full_name, u.phone
                  from contact c join app_user u on u.id = c.contact_user_id
                 where c.owner_user_id = :owner and u.status = 'ACTIVE'
                 order by u.full_name""")
                .param("owner", ownerId)
                .query((rs, n) -> new ContactRow(rs.getObject("id", UUID.class), rs.getString("full_name"),
                        rs.getString("phone")))
                .list();
    }

    public boolean exists(UUID ownerId, UUID contactId) {
        return jdbc.sql("select count(*) from contact where owner_user_id = :o and contact_user_id = :c")
                .param("o", ownerId).param("c", contactId)
                .query(Integer.class).single() > 0;
    }

    /** Supuesto de demo: todo usuario activado recibe como contactos a los usuarios marcados como demo. */
    public void addDemoContacts(UUID ownerId) {
        jdbc.sql("""
                insert into contact (owner_user_id, contact_user_id)
                select :owner, id from app_user where demo_contact and id <> :owner
                on conflict do nothing""")
                .param("owner", ownerId).update();
    }
}
