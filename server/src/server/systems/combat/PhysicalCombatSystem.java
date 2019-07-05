package server.systems.combat;

import com.artemis.E;
import com.artemis.annotations.Wire;
import com.esotericsoftware.minlog.Log;
import entity.character.states.Heading;
import entity.character.status.Health;
import entity.world.CombatMessage;
import graphics.Effect;
import physics.AttackAnimation;
import position.WorldPos;
import server.database.model.modifiers.Modifiers;
import server.systems.CharacterTrainingSystem;
import server.systems.manager.MapManager;
import server.systems.manager.ObjectManager;
import server.systems.manager.WorldManager;
import shared.interfaces.CharClass;
import shared.interfaces.FXs;
import shared.network.notifications.ConsoleMessage;
import shared.network.notifications.EntityUpdate;
import shared.network.notifications.EntityUpdate.EntityUpdateBuilder;
import shared.network.sound.SoundNotification;
import shared.objects.types.*;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static com.artemis.E.E;
import static java.lang.String.format;
import static server.utils.WorldUtils.WorldUtils;
import static shared.util.Messages.*;

@Wire
public class PhysicalCombatSystem extends AbstractCombatSystem {

    // Injected Systems
    private MapManager mapManager;
    private ObjectManager objectManager;
    private WorldManager worldManager;
    private CharacterTrainingSystem characterTrainingSystem;


    private static final String MISS = "MISS";
    private static final float ASSASIN_STAB_FACTOR = 1.5f;
    private static final float NORMAL_STAB_FACTOR = 1.4f;
    private static final int TIME_TO_MOVE_1_TILE = 250;

    @Override
    protected void failed(int entityId, Optional<Integer> targetId) {
        notify(targetId.isPresent() ? targetId.get() : entityId, CombatMessage.physic(MISS));
    }

    @Override
    public boolean canAttack(int entityId, Optional<Integer> target) {
        final E e = E(entityId);
        if (e != null && e.hasStamina() && e.getStamina().min < e.getStamina().max * STAMINA_REQUIRED_PERCENT / 100) {
            notifyCombat(entityId, NOT_ENOUGH_ENERGY);
            return false;
        }
        if (e != null && e.hasHealth() && e.getHealth().min == 0) {
            notifyCombat(entityId, DEAD_CANT_ATTACK);
            return false;
        }

        if (target.isPresent()) {
            int targetId = target.get();
            E t = E(targetId);
            if (t == null) {
                Log.info("Can't find target");
                return false;
            }
            if (!isValidTarget(entityId, targetId)) {

                return false;
            }

            if (t.hasHealth() && t.getHealth().min == 0) {
                // no podes atacar un muerto
                notifyCombat(entityId, CANT_ATTACK_DEAD);
                return false;
            }

            // es del otro team? ciuda - crimi
            if (!e.isCriminal() && !t.isCriminal() /* TODO agregar seguro */) {
                // notifyCombat(userId, CANT_ATTACK_CITIZEN);
                // TODO descomentar: return false;
            }

            // TODO attack power can be bow

            int evasionPower = evasionPower(targetId) + (E(targetId).hasShield() ? shieldEvasionPower(targetId) : 0);
            double prob = Math.max(10, Math.min(90, 50 + (weaponAttackPower(entityId) - evasionPower) * 0.4));
            if (ThreadLocalRandom.current().nextInt(101) <= prob) {
                return true;
            } else {
                int skills = 200;
                prob = Math.max(10, Math.min(90, 100 * 100 / skills));

                // shield evasion
                if (E(targetId).hasShield() && ThreadLocalRandom.current().nextInt(101) <= prob) {
                    notifyCombat(targetId, SHIELD_DEFENSE);
                    notifyCombat(entityId, format(DEFENDED_WITH_SHIELD, getName(targetId)));
                    // TODO shield animation
                    worldManager.notifyUpdate(targetId, new SoundNotification(37));
                } else {
                    notifyCombat(entityId, ATTACK_FAILED);
                    notifyCombat(targetId, format(ATTACKED_AND_FAILED, getName(entityId)));

                }
            }
        }
        return false;
    }

    private boolean isValidTarget(int entityId, int targetId) {
        if (E(entityId).hasNPC()) {
            return E(targetId).isCharacter();
        }
        return E(targetId).isCharacter() || (E(targetId).hasNPC() && E(targetId).isAttackable());
    }

    @Override
    public int damageCalculation(int userId, int entityId) {
        E entity = E(userId);
        final Optional<Obj> obj = entity.hasWeapon() ? objectManager.getObject(entity.getWeapon().index) : Optional.empty();
        final Optional<WeaponObj> weapon =
                obj.isPresent() && Type.WEAPON.equals(obj.get().getType()) ? Optional.of((WeaponObj) obj.get()) : Optional.empty();

        int baseDamage = getBaseDamage(entity, weapon);
        Log.info("Base Damage: " + baseDamage);
        AttackPlace place = AttackPlace.getRandom();
        int defense = (place == AttackPlace.HEAD ? getHeadDefense(entityId) : getBodyDefense(entityId));
        Log.info("Defense: " + defense);
        return Math.max(0, baseDamage - defense);
    }

    private int getBodyDefense(int entityId) {
        int min = 0, max = 1;
        E entity = E(entityId);
        if (entity.hasArmor()) {
            int index = entity.getArmor().getIndex();
            ArmorObj armorObj = (ArmorObj) objectManager.getObject(index).get();
            min = armorObj.getMinDef();
            max = armorObj.getMaxDef();
        }
        if (entity.hasShield()) {
            int index = entity.getShield().index;
            ShieldObj shieldObj = (ShieldObj) objectManager.getObject(index).get();
            min += shieldObj.getMinDef();
            max += shieldObj.getMaxDef();
        }
        return ThreadLocalRandom.current().nextInt(min, max);
    }

    private int getHeadDefense(int entityId) {
        int min = 0, max = 1;
        E entity = E(entityId);
        if (entity.hasHelmet()) {
            int index = entity.getHelmet().index;
            HelmetObj obj = (HelmetObj) objectManager.getObject(index).get();
            min = obj.getMinDef();
            max = obj.getMaxDef();
        }
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private int getBaseDamage(E entity, Optional<WeaponObj> weapon) {
        int baseDamage = 0;
        if (entity.hasCharHero()) {
            CharClass clazz = CharClass.of(entity);
            AttackKind kind = AttackKind.getKind(entity);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            float modifier = kind == AttackKind.PROJECTILE ?
                    Modifiers.PROJECTILE_DAMAGE.of(clazz) :
                    kind == AttackKind.WEAPON ? Modifiers.WEAPON_DAMAGE.of(clazz) : Modifiers.WRESTLING_DAMAGE.of(clazz);
            Log.info("Modifier: " + modifier);
            int weaponDamage =
                    weapon.map(weaponObj -> random.nextInt(weaponObj.getMinHit(), weaponObj.getMaxHit() + 1))
                            .orElseGet(() -> random.nextInt(4, 9));
            Log.info("Weapon Damage: " + weaponDamage);
            int maxWeaponDamage = weapon.map(WeaponObj::getMaxHit).orElse(9);
            Log.info("Max Weapon Damage: " + maxWeaponDamage);
            int userDamage = random.nextInt(entity.getHit().getMin() - 10, entity.getHit().getMax() + 1);
            Log.info("User damage: " + userDamage);
            baseDamage = (int) ((3 * weaponDamage + ((maxWeaponDamage) / 5) * Math.max(0, entity.strengthCurrentValue() - 15) + userDamage)
                    * modifier);
        } else if (entity.hasHit()) {
            baseDamage = ThreadLocalRandom.current().nextInt(Math.max(0, entity.getHit().getMin()), entity.getHit().getMax() + 1);
        }
        return baseDamage;
    }

    @Override
    Optional<Integer> getTarget(int userId) {
        E entity = E(userId);
        Heading headingTo = entity.getHeading();
        WorldPos worldPos = entity.getWorldPos();
        WorldPos targetPos = WorldUtils(world).getFacingPos(worldPos, headingTo);
        return mapManager
                .getNearEntities(userId)
                .stream()
                .filter(
                        targetId -> isEffectiveTarget(targetPos, targetId))
                .findFirst();
    }

    private boolean isEffectiveTarget(WorldPos targetPos, Integer targetId) {
        return E(targetId).hasWorldPos() && isAttackable(targetId) && (E(targetId).getWorldPos().equals(targetPos) || footprintOf(targetId, targetPos, System.currentTimeMillis()));
    }

    private boolean footprintOf(Integer entity, WorldPos worldPos, long timestamp) {
        final Set<Integer> footprints = mapManager.getEntitiesFootprints().get(entity);
        return footprints != null && footprints
                .stream()
                .anyMatch(footprint -> worldPos.equals(E(footprint).getWorldPos()) && timestamp - E(footprint).getFootprint().timestamp < TIME_TO_MOVE_1_TILE);
    }

    @Override
    void doHit(int userId, int entityId, int damage) {
        boolean userStab = canStab(userId);
        AttackResult result =
                userStab ?
                        doStab(userId, entityId, damage) :
                        canCriticAttack(userId, entityId) ?
                                doCrititAttack(userId, entityId, damage) :
                                doNormalAttack(userId, entityId, damage);

        notifyCombat(userId, result.userMessage);
        notifyCombat(entityId, result.victimMessage);


        worldManager
                .notifyUpdate(userId, EntityUpdateBuilder.of(userId).withComponents(new AttackAnimation()).build());
        notify(entityId, userStab ? CombatMessage.stab("" + result.damage) : CombatMessage.physic("" + result.damage));

        final E target = E(entityId);
        Health health = target.getHealth();
        int effectiveDamage = Math.min(health.min, result.damage);
        characterTrainingSystem.userTakeDamage(userId, entityId, effectiveDamage);
        health.min = Math.max(0, health.min - result.damage);
        sendFX(entityId);
        if (health.min > 0) {
            update(entityId);
        } else {
            // TODO die
            characterTrainingSystem.takeGold(userId, entityId);
            notifyCombat(userId, format(KILL, getName(entityId)));
            notifyCombat(entityId, format(KILLED, getName(userId)));
            worldManager.entityDie(entityId);
        }
    }

    private void notifyCombat(int userId, String message) {
        final ConsoleMessage combat = ConsoleMessage.combat(message);
        worldManager.sendEntityUpdate(userId, combat);
    }

    private AttackResult doNormalAttack(int userId, int entityId, int damage) {
        return new AttackResult(damage, format(USER_NORMAL_HIT, getName(entityId), damage),
                format(VICTIM_NORMAL_HIT, getName(userId), damage));
    }

    private AttackResult doCrititAttack(int userId, int entityId, int damage) {
        // TODO
        return new AttackResult(damage, format(USER_CRITIC_HIT, getName(entityId), damage),
                format(VICTIM_CRITIC_HIT, getName(userId), damage));
    }

    private boolean canCriticAttack(int userId, int entityId) {
        return false;
    }

    private boolean canStab(int userId) {
        final E e = E(userId);
        boolean result = false;
        if (e.hasWeapon()) {
            final Optional<Obj> object = objectManager.getObject(e.getWeapon().index);
            result = object
                    .filter(WeaponObj.class::isInstance)
                    .map(WeaponObj.class::cast)
                    .filter(WeaponObj::isStab)
                    .isPresent();
        }

        return result && stabProbability(userId);
    }

    private boolean stabProbability(int userId) {
        float skill = 100;
        int lucky;
        E e = E(userId);
        final CharClass clazz = CharClass.of(e);
        switch (clazz) {
            case ASSASSIN:
                lucky = (int) (((0.00003f * skill - 0.002) * skill + 0.098f) * skill + 4.25f);
                break;
            case CLERIC:
            case PALADIN:
            case PIRATE:
                lucky = (int) (((0.000003f * skill - 0.0006f) * skill + 0.0107f) * skill + 4.93f);
                break;
            case BARDIC:
                lucky = (int) (((0.000002f * skill - 0.0002f) * skill + 0.032f) * skill + 4.81f);
                break;
            default:
                lucky = (int) (0.0361f * skill + 4.39f);
                break;

        }
        return ThreadLocalRandom.current().nextInt(101) < lucky;
    }

    private AttackResult doStab(int userId, int entityId, int damage) {
        final CharClass clazz = CharClass.of(E(userId));
        damage += (int) (CharClass.ASSASSIN.equals(clazz) ? damage * ASSASIN_STAB_FACTOR : damage * NORMAL_STAB_FACTOR);
        return new AttackResult(damage, format(USER_STAB_HIT, getName(entityId), damage),
                format(VICTIM_STAB_HIT, getName(userId), damage));
    }

    private String getName(int userId) {
        return E(userId).getName().text;
    }

    @Override
    boolean isAttackable(int entityId) {
        return E(entityId).hasNPC() || E(entityId).isCharacter();
    }

    /**
     * Send combat notification to user and near by entities
     *
     * @param victim        entity id
     * @param combatMessage message
     */
    private void notify(int victim, CombatMessage combatMessage) {
        worldManager
                .notifyUpdate(victim, EntityUpdateBuilder.of(victim).withComponents(combatMessage).build());
    }

    /**
     * Send an update to entity with current health
     *
     * @param victim entity id
     */
    private void update(int victim) {
        E v = E(victim);
        EntityUpdate update = EntityUpdateBuilder.of(victim).withComponents(v.getHealth()).build();
        worldManager.sendEntityUpdate(victim, update);
    }

    private void sendFX(int victim) {
        E v = E(victim);
        int fxE = world.create();
        EntityUpdateBuilder fxUpdate = EntityUpdateBuilder.of(fxE);
        Effect effect = new Effect.EffectBuilder().attachTo(victim).withLoops(1).withFX(FXs.FX_BLOOD).build();
        fxUpdate.withComponents(effect);
        if (v.hasWorldPos()) {
            WorldPos worldPos = v.getWorldPos();
            fxUpdate.withComponents(worldPos);
        }
        worldManager.notifyUpdate(victim, fxUpdate.build());
        world.delete(fxE);
    }

    private enum AttackKind {
        WEAPON,
        PROJECTILE,
        WRESTLING;

        protected static AttackKind getKind(E entity) {
            return entity.hasWeapon() ? WEAPON : WRESTLING;
        }
    }


    private enum AttackPlace {
        HEAD,
        BODY;

        private static final List<AttackPlace> VALUES =
                Collections.unmodifiableList(Arrays.asList(values()));
        private static final int SIZE = VALUES.size();
        private static final Random RANDOM = new Random();

        public static AttackPlace getRandom() {
            return VALUES.get(RANDOM.nextInt(SIZE));
        }
    }

    private static class AttackResult {

        private final int damage;
        private final String userMessage;
        private String victimMessage;

        AttackResult(int damage, String userMessage, String victimMessage) {

            this.damage = damage;
            this.userMessage = userMessage;
            this.victimMessage = victimMessage;
        }

    }
}