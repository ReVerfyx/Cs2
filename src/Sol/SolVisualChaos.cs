using System.Drawing;
using CounterStrikeSharp.API;
using CounterStrikeSharp.API.Core;
using CounterStrikeSharp.API.Modules.Timers;
using CounterStrikeSharp.API.Modules.Utils;
using Microsoft.Extensions.Logging;

namespace Sol;

public sealed partial class SolPlugin
{
    private readonly List<CEntityInstance> _chaosEntities = new();

    private static readonly string ChairModel = "models/generic/terrace_set_01/terrace_chair_01.vmdl";
    private static readonly string C4Model = "weapons/models/c4/weapon_c4.vmdl";

    private void RegisterChaosResources()
    {
        RegisterListener<Listeners.OnServerPrecacheResources>(manifest =>
        {
            foreach (var resource in new[]
            {
                Config.DroneModel,
                Config.TankBodyModel,
                Config.TankTurretModel,
                Config.MissileModel,
                Config.MeteorModel,
                Config.AirPlantModel,
                ChairModel,
                C4Model,
                "models/chicken/chicken.vmdl"
            }.Where(x => !string.IsNullOrWhiteSpace(x)).Distinct(StringComparer.OrdinalIgnoreCase))
            {
                manifest.AddResource(resource);
            }
        });
    }

    private T? Track<T>(T? entity) where T : CEntityInstance
    {
        if (entity is { IsValid: true })
            _chaosEntities.Add(entity);

        return entity;
    }

    private void CleanupChaosEntities()
    {
        foreach (var entity in _chaosEntities.ToArray())
        {
            try
            {
                if (entity.IsValid)
                    entity.Remove();
            }
            catch
            {
                // Source 2 may already have removed an entity during a round transition.
            }
        }

        _chaosEntities.Clear();
    }

    private CDynamicProp? SpawnDynamic(string model, Vector origin, QAngle? angles = null)
    {
        if (!Config.EnableVisualEntities || string.IsNullOrWhiteSpace(model))
            return null;

        try
        {
            var prop = Utilities.CreateEntityByName<CDynamicProp>("prop_dynamic");
            if (prop is null) return null;

            prop.SetModel(model);
            prop.DispatchSpawn();
            prop.Teleport(origin, angles ?? new QAngle(0, 0, 0), null);

            return Track(prop);
        }
        catch (Exception ex)
        {
            Logger.LogWarning(ex, "Не удалось создать prop_dynamic с моделью {Model}", model);
            return null;
        }
    }

    private CPhysicsProp? SpawnPhysics(string model, Vector origin, Vector velocity)
    {
        if (!Config.EnableVisualEntities || string.IsNullOrWhiteSpace(model))
            return null;

        try
        {
            var prop = Utilities.CreateEntityByName<CPhysicsProp>("prop_physics");
            if (prop is null) return null;

            prop.SetModel(model);
            prop.DispatchSpawn();
            prop.Teleport(origin, new QAngle(
                _random.Next(-180, 181),
                _random.Next(-180, 181),
                _random.Next(-180, 181)), velocity);

            return Track(prop);
        }
        catch (Exception ex)
        {
            Logger.LogWarning(ex, "Не удалось создать prop_physics с моделью {Model}", model);
            return null;
        }
    }

    private CPointWorldText? SpawnWorldText(string text, Vector origin)
    {
        if (!Config.EnableVisualEntities) return null;

        try
        {
            var worldText = Utilities.CreateEntityByName<CPointWorldText>("point_worldtext");
            if (worldText is null) return null;

            worldText.MessageText = text;
            worldText.FontSize = 36f;
            worldText.WorldUnitsPerPx = 0.25f;
            worldText.Fullbright = true;
            worldText.Color = Color.FromArgb(255, 255, 70, 70);
            worldText.DispatchSpawn();
            worldText.Teleport(origin, new QAngle(0, 0, 0), null);

            return Track(worldText);
        }
        catch (Exception ex)
        {
            Logger.LogWarning(ex, "Не удалось создать point_worldtext");
            return null;
        }
    }

    private void ExplosionFx(Vector origin, int radius = 260, int damage = 70)
    {
        if (Config.EnableVisualEntities)
        {
            try
            {
                var explosion = Utilities.CreateEntityByName<CEnvExplosion>("env_explosion");
                if (explosion is not null)
                {
                    explosion.DispatchSpawn();
                    explosion.Teleport(origin, new QAngle(0, 0, 0), null);
                    explosion.AcceptInput("Explode");
                    Track(explosion);
                }
            }
            catch (Exception ex)
            {
                Logger.LogDebug(ex, "env_explosion недоступен; оставляем серверный урон.");
            }
        }

        foreach (var player in AlivePlayers())
        {
            var pawn = Pawn(player);
            if (pawn?.AbsOrigin is null) continue;

            var distance = Distance(pawn.AbsOrigin, origin);
            if (distance > radius) continue;

            var scaled = Math.Max(1, (int)(damage * (1f - distance / radius)));
            Damage(player, scaled, explode: true);

            var dx = pawn.AbsOrigin.X - origin.X;
            var dy = pawn.AbsOrigin.Y - origin.Y;
            var len = MathF.Max(1f, MathF.Sqrt(dx * dx + dy * dy));
            pawn.Teleport(null, null, new Vector(dx / len * 360f, dy / len * 360f, 420f));
        }
    }

    private void SpawnAirPlantVisual()
    {
        if (_airPlantPoint is null || !Config.EnableVisualEntities) return;

        var point = _airPlantPoint;
        SpawnDynamic(Config.AirPlantModel, point, new QAngle(0, 0, 0));
        SpawnDynamic(C4Model, new Vector(point.X, point.Y, point.Z + 55f), new QAngle(0, 0, 0));
        SpawnWorldText("ПЛЭНТ В ВОЗДУХЕ", new Vector(point.X, point.Y, point.Z + 105f));
    }

    private void AddTankVisual(CCSPlayerController tank, int token)
    {
        if (!Config.EnableVisualEntities) return;

        var pawn = Pawn(tank);
        if (pawn?.AbsOrigin is null) return;

        var body = SpawnDynamic(Config.TankBodyModel, Copy(pawn.AbsOrigin));
        var turret = SpawnDynamic(Config.TankTurretModel,
            new Vector(pawn.AbsOrigin.X, pawn.AbsOrigin.Y, pawn.AbsOrigin.Z + 65f));

        if (body is null && turret is null) return;

        CounterStrikeSharp.API.Modules.Timers.Timer? followTimer = null;
        followTimer = AddTimer(0.1f, () =>
        {
            if (token != _roundToken)
            {
                followTimer?.Kill();
                return;
            }

            var live = AlivePlayers().FirstOrDefault(p => p.Slot == tank.Slot);
            var livePawn = live is null ? null : Pawn(live);

            if (livePawn?.AbsOrigin is null)
            {
                followTimer?.Kill();
                return;
            }

            var origin = livePawn.AbsOrigin;
            var angle = livePawn.AbsRotation ?? new QAngle(0, 0, 0);

            if (body is { IsValid: true })
                body.Teleport(new Vector(origin.X, origin.Y, origin.Z - 12f), angle, null);

            if (turret is { IsValid: true })
                turret.Teleport(new Vector(origin.X, origin.Y, origin.Z + 62f), angle, null);
        }, TimerFlags.REPEAT | TimerFlags.STOP_ON_MAPCHANGE);
    }

    private void LaunchVisualMissile(CCSPlayerController target, int token)
    {
        if (!Config.EnableVisualEntities) return;

        var pawn = Pawn(target);
        if (pawn?.AbsOrigin is null) return;

        var start = new Vector(
            pawn.AbsOrigin.X + _random.Next(-700, 701),
            pawn.AbsOrigin.Y + _random.Next(-700, 701),
            pawn.AbsOrigin.Z + _random.Next(650, 950));

        var missile = SpawnDynamic(Config.MissileModel, start);
        if (missile is null) return;

        var steps = 0;
        CounterStrikeSharp.API.Modules.Timers.Timer? missileTimer = null;
        missileTimer = AddTimer(0.05f, () =>
        {
            if (token != _roundToken || missile is not { IsValid: true })
            {
                missileTimer?.Kill();
                return;
            }

            var live = AlivePlayers().FirstOrDefault(p => p.Slot == target.Slot);
            var livePawn = live is null ? null : Pawn(live);

            if (livePawn?.AbsOrigin is null || missile.AbsOrigin is null)
            {
                missileTimer?.Kill();
                if (missile.IsValid) missile.Remove();
                return;
            }

            steps++;
            var from = missile.AbsOrigin;
            var to = livePawn.AbsOrigin;
            var alpha = 0.14f;

            var next = new Vector(
                from.X + (to.X - from.X) * alpha,
                from.Y + (to.Y - from.Y) * alpha,
                from.Z + (to.Z - from.Z) * alpha);

            missile.Teleport(next, missile.AbsRotation, null);

            if (Distance(next, to) < 55f || steps > 60)
            {
                missileTimer?.Kill();
                var blast = Copy(to);
                if (missile.IsValid) missile.Remove();
                ExplosionFx(blast, 300, 150);
            }
        }, TimerFlags.REPEAT | TimerFlags.STOP_ON_MAPCHANGE);
    }

    private void LaunchVisualDrone(CCSPlayerController target, int token)
    {
        if (!Config.EnableVisualEntities) return;

        var pawn = Pawn(target);
        if (pawn?.AbsOrigin is null) return;

        var drone = SpawnDynamic(Config.DroneModel, new Vector(
            pawn.AbsOrigin.X + _random.Next(-500, 501),
            pawn.AbsOrigin.Y + _random.Next(-500, 501),
            pawn.AbsOrigin.Z + _random.Next(450, 750)));

        if (drone is null) return;

        var steps = 0;
        CounterStrikeSharp.API.Modules.Timers.Timer? flyTimer = null;
        flyTimer = AddTimer(0.08f, () =>
        {
            if (token != _roundToken || drone is not { IsValid: true })
            {
                flyTimer?.Kill();
                return;
            }

            var live = AlivePlayers().FirstOrDefault(p => p.Slot == target.Slot);
            var livePawn = live is null ? null : Pawn(live);

            if (livePawn?.AbsOrigin is null || drone.AbsOrigin is null)
            {
                flyTimer?.Kill();
                if (drone.IsValid) drone.Remove();
                return;
            }

            steps++;
            var from = drone.AbsOrigin;
            var to = livePawn.AbsOrigin;
            var alpha = 0.075f;

            var next = new Vector(
                from.X + (to.X - from.X) * alpha,
                from.Y + (to.Y - from.Y) * alpha,
                from.Z + (to.Z + 35f - from.Z) * alpha);

            drone.Teleport(next, new QAngle(0, (steps * 18) % 360, 0), null);

            if (Distance(next, to) < 70f || steps > 90)
            {
                flyTimer?.Kill();
                var blast = Copy(to);
                if (drone.IsValid) drone.Remove();
                ExplosionFx(blast, 260, 95);
            }
        }, TimerFlags.REPEAT | TimerFlags.STOP_ON_MAPCHANGE);
    }

    private void MeteorRain(int token)
    {
        Broadcast("[СОЛЬ] МЕТЕОРИТНЫЙ ДОЖДЬ. Да, это бочки.");

        CounterStrikeSharp.API.Modules.Timers.Timer? timer = null;
        timer = AddTimer(Config.MeteorIntervalSeconds, () =>
        {
            if (token != _roundToken)
            {
                timer?.Kill();
                return;
            }

            var victim = Pick(AlivePlayers());
            var pawn = victim is null ? null : Pawn(victim);
            if (victim is null || pawn?.AbsOrigin is null) return;

            var impact = Copy(pawn.AbsOrigin);
            var origin = new Vector(
                impact.X + _random.Next(-250, 251),
                impact.Y + _random.Next(-250, 251),
                impact.Z + _random.Next(700, 1100));

            var meteor = SpawnPhysics(Config.MeteorModel, origin,
                new Vector(_random.Next(-80, 81), _random.Next(-80, 81), -950f));

            SpawnWorldText("☄", new Vector(origin.X, origin.Y, origin.Z + 80f));

            AddTimer(0.9f, () =>
            {
                if (token != _roundToken) return;

                var blast = meteor is { IsValid: true } && meteor.AbsOrigin is not null
                    ? Copy(meteor.AbsOrigin)
                    : impact;

                if (meteor is { IsValid: true }) meteor.Remove();
                ExplosionFx(blast, 280, 80);
            }, TimerFlags.STOP_ON_MAPCHANGE);
        }, TimerFlags.REPEAT | TimerFlags.STOP_ON_MAPCHANGE);
    }

    private void ChickenRain(int token)
    {
        Broadcast("[СОЛЬ] ДОЖДЬ ИЗ КУР. Почему? Потому.");

        var players = AlivePlayers();
        if (players.Count == 0) return;

        for (var i = 0; i < Config.ChickenRainCount; i++)
        {
            var anchor = Pick(players);
            var pawn = anchor is null ? null : Pawn(anchor);
            if (pawn?.AbsOrigin is null) continue;

            try
            {
                var chicken = Utilities.CreateEntityByName<CChicken>("chicken");
                if (chicken is null) continue;

                chicken.DispatchSpawn();
                chicken.Teleport(new Vector(
                    pawn.AbsOrigin.X + _random.Next(-400, 401),
                    pawn.AbsOrigin.Y + _random.Next(-400, 401),
                    pawn.AbsOrigin.Z + _random.Next(300, 750)),
                    new QAngle(0, _random.Next(0, 360), 0),
                    new Vector(_random.Next(-70, 71), _random.Next(-70, 71), -80f));

                Track(chicken);
            }
            catch (Exception ex)
            {
                Logger.LogDebug(ex, "Курица отказалась участвовать в хаосе.");
            }
        }
    }

    private void BlackHole(int token)
    {
        var anchorPlayer = Pick(AlivePlayers());
        var anchorPawn = anchorPlayer is null ? null : Pawn(anchorPlayer);
        if (anchorPawn?.AbsOrigin is null) return;

        var center = new Vector(
            anchorPawn.AbsOrigin.X + _random.Next(-300, 301),
            anchorPawn.AbsOrigin.Y + _random.Next(-300, 301),
            anchorPawn.AbsOrigin.Z + 120f);

        SpawnDynamic(C4Model, center);
        SpawnWorldText("ЧЁРНАЯ ДЫРА", new Vector(center.X, center.Y, center.Z + 100f));
        Broadcast("[СОЛЬ] ЧЁРНАЯ ДЫРА. Бежать бесполезно, но можно попробовать.");

        var ticks = 0;
        CounterStrikeSharp.API.Modules.Timers.Timer? timer = null;
        timer = AddTimer(0.15f, () =>
        {
            if (token != _roundToken || ticks++ > 85)
            {
                timer?.Kill();
                return;
            }

            foreach (var player in AlivePlayers())
            {
                var pawn = Pawn(player);
                if (pawn?.AbsOrigin is null) continue;

                var distance = Distance(pawn.AbsOrigin, center);
                if (distance > 1200f) continue;

                var dx = center.X - pawn.AbsOrigin.X;
                var dy = center.Y - pawn.AbsOrigin.Y;
                var dz = center.Z - pawn.AbsOrigin.Z;
                var len = MathF.Max(1f, MathF.Sqrt(dx * dx + dy * dy + dz * dz));
                var strength = MathF.Min(900f, 180f + (1200f - distance) * 0.7f);

                pawn.Teleport(null, null, new Vector(
                    dx / len * strength,
                    dy / len * strength,
                    dz / len * strength + 80f));

                if (distance < 90f)
                    Damage(player, 12, explode: false);
            }
        }, TimerFlags.REPEAT | TimerFlags.STOP_ON_MAPCHANGE);
    }

    private void FurnitureTornado(int token)
    {
        var anchorPlayer = Pick(AlivePlayers());
        var anchorPawn = anchorPlayer is null ? null : Pawn(anchorPlayer);
        if (anchorPawn?.AbsOrigin is null) return;

        var center = Copy(anchorPawn.AbsOrigin);
        var props = new List<CDynamicProp>();

        for (var i = 0; i < 9; i++)
        {
            var prop = SpawnDynamic(ChairModel, new Vector(center.X, center.Y, center.Z + 100f));
            if (prop is not null) props.Add(prop);
        }

        if (props.Count == 0) return;

        Broadcast("[СОЛЬ] МЕБЕЛЬНЫЙ ТОРНАДО.");

        var tick = 0;
        CounterStrikeSharp.API.Modules.Timers.Timer? timer = null;
        timer = AddTimer(0.08f, () =>
        {
            if (token != _roundToken || tick++ > 180)
            {
                timer?.Kill();
                return;
            }

            var t = tick * 0.16f;
            for (var i = 0; i < props.Count; i++)
            {
                var prop = props[i];
                if (!prop.IsValid) continue;

                var phase = t + i * (MathF.PI * 2f / props.Count);
                var radius = 190f + (i % 3) * 55f;
                var z = center.Z + 80f + (i % 4) * 70f + MathF.Sin(t * 1.8f + i) * 55f;

                prop.Teleport(new Vector(
                    center.X + MathF.Cos(phase) * radius,
                    center.Y + MathF.Sin(phase) * radius,
                    z),
                    new QAngle(tick * 4 % 360, tick * 7 % 360, tick * 3 % 360),
                    null);
            }

            foreach (var player in AlivePlayers())
            {
                var pawn = Pawn(player);
                if (pawn?.AbsOrigin is null) continue;

                if (Distance(pawn.AbsOrigin, center) < 330f && tick % 8 == 0)
                    pawn.Teleport(null, null, new Vector(
                        _random.Next(-450, 451),
                        _random.Next(-450, 451),
                        _random.Next(250, 650)));
            }
        }, TimerFlags.REPEAT | TimerFlags.STOP_ON_MAPCHANGE);
    }

    private void WallOfSafes(int token)
    {
        var victim = Pick(AlivePlayers());
        var pawn = victim is null ? null : Pawn(victim);
        if (pawn?.AbsOrigin is null) return;

        var start = new Vector(pawn.AbsOrigin.X - 900f, pawn.AbsOrigin.Y, pawn.AbsOrigin.Z);
        var props = new List<CDynamicProp>();

        for (var i = -2; i <= 2; i++)
        {
            var prop = SpawnDynamic(Config.TankBodyModel,
                new Vector(start.X, start.Y + i * 95f, start.Z));
            if (prop is not null) props.Add(prop);
        }

        Broadcast("[СОЛЬ] СТЕНА ИЗ СЕЙФОВ ЕДЕТ ЧЕРЕЗ КАРТУ.");

        var tick = 0;
        CounterStrikeSharp.API.Modules.Timers.Timer? timer = null;
        timer = AddTimer(0.06f, () =>
        {
            if (token != _roundToken || tick++ > 260)
            {
                timer?.Kill();
                return;
            }

            var x = start.X + tick * 10f;
            for (var i = 0; i < props.Count; i++)
            {
                var prop = props[i];
                if (prop.IsValid)
                    prop.Teleport(new Vector(x, start.Y + (i - 2) * 95f, start.Z), new QAngle(0, 90, 0), null);
            }

            if (tick % 4 != 0) return;

            foreach (var player in AlivePlayers())
            {
                var p = Pawn(player);
                if (p?.AbsOrigin is null) continue;

                if (MathF.Abs(p.AbsOrigin.X - x) < 75f && MathF.Abs(p.AbsOrigin.Y - start.Y) < 300f)
                {
                    p.Teleport(null, null, new Vector(700f, _random.Next(-250, 251), 300f));
                    Damage(player, 18, explode: false);
                }
            }
        }, TimerFlags.REPEAT | TimerFlags.STOP_ON_MAPCHANGE);
    }


    private void SpawnRoundJunk(int token)
    {
        if (!Config.EnableVisualEntities) return;

        var players = AlivePlayers();
        if (players.Count == 0) return;

        string[] models = [ChairModel, C4Model, Config.TankBodyModel, Config.DroneModel];
        var count = _random.Next(7, 14);

        for (var i = 0; i < count; i++)
        {
            var anchor = Pick(players);
            var pawn = anchor is null ? null : Pawn(anchor);
            if (pawn?.AbsOrigin is null) continue;

            var model = models[_random.Next(models.Length)];
            SpawnDynamic(model, new Vector(
                pawn.AbsOrigin.X + _random.Next(-650, 651),
                pawn.AbsOrigin.Y + _random.Next(-650, 651),
                pawn.AbsOrigin.Z + _random.Next(180, 650)),
                new QAngle(_random.Next(0, 360), _random.Next(0, 360), _random.Next(0, 360)));
        }

        Broadcast("[СОЛЬ] Небо опять завалило случайным мусором.");
    }

    private void SpawnPvoBattery()
    {
        if (!Config.EnableVisualEntities) return;

        var anchors = AlivePlayers().OrderBy(_ => _random.Next()).Take(2).ToList();
        foreach (var player in anchors)
        {
            var pawn = Pawn(player);
            if (pawn?.AbsOrigin is null) continue;

            var pos = new Vector(
                pawn.AbsOrigin.X + _random.Next(-260, 261),
                pawn.AbsOrigin.Y + _random.Next(-260, 261),
                pawn.AbsOrigin.Z);

            SpawnDynamic(Config.DroneModel, pos, new QAngle(0, _random.Next(0, 360), 0));
            SpawnWorldText("ПВО", new Vector(pos.X, pos.Y, pos.Z + 95f));
        }
    }

    private void StartTankCannon(CCSPlayerController tank, int token)
    {
        CounterStrikeSharp.API.Modules.Timers.Timer? timer = null;
        timer = AddTimer(6.0f, () =>
        {
            if (token != _roundToken)
            {
                timer?.Kill();
                return;
            }

            var liveTank = AlivePlayers().FirstOrDefault(p => p.Slot == tank.Slot);
            if (liveTank is null)
            {
                timer?.Kill();
                return;
            }

            var enemyTeam = liveTank.Team == CsTeam.Terrorist ? CsTeam.CounterTerrorist : CsTeam.Terrorist;
            var target = Pick(AlivePlayers(enemyTeam));
            if (target is null) return;

            liveTank.PrintToCenter($"ТАНКОВЫЙ ВЫСТРЕЛ → {target.PlayerName}");
            LaunchTankShell(liveTank, target, token);
        }, TimerFlags.REPEAT | TimerFlags.STOP_ON_MAPCHANGE);
    }

    private void LaunchTankShell(CCSPlayerController tank, CCSPlayerController target, int token)
    {
        var tankPawn = Pawn(tank);
        var targetPawn = Pawn(target);
        if (tankPawn?.AbsOrigin is null || targetPawn?.AbsOrigin is null) return;

        if (!Config.EnableVisualEntities)
        {
            AddTimer(0.8f, () =>
            {
                if (token != _roundToken) return;
                var live = AlivePlayers().FirstOrDefault(p => p.Slot == target.Slot);
                if (live is not null) Damage(live, 95, explode: true);
            }, TimerFlags.STOP_ON_MAPCHANGE);
            return;
        }

        var shell = SpawnDynamic(Config.MissileModel, new Vector(
            tankPawn.AbsOrigin.X,
            tankPawn.AbsOrigin.Y,
            tankPawn.AbsOrigin.Z + 65f));

        if (shell is null) return;

        var steps = 0;
        CounterStrikeSharp.API.Modules.Timers.Timer? shellTimer = null;
        shellTimer = AddTimer(0.05f, () =>
        {
            if (token != _roundToken || shell is not { IsValid: true })
            {
                shellTimer?.Kill();
                return;
            }

            var liveTarget = AlivePlayers().FirstOrDefault(p => p.Slot == target.Slot);
            var livePawn = liveTarget is null ? null : Pawn(liveTarget);

            if (livePawn?.AbsOrigin is null || shell.AbsOrigin is null)
            {
                shellTimer?.Kill();
                if (shell.IsValid) shell.Remove();
                return;
            }

            steps++;
            var from = shell.AbsOrigin;
            var to = livePawn.AbsOrigin;
            var alpha = 0.16f;
            var next = new Vector(
                from.X + (to.X - from.X) * alpha,
                from.Y + (to.Y - from.Y) * alpha,
                from.Z + (to.Z + 20f - from.Z) * alpha);

            shell.Teleport(next, shell.AbsRotation, null);

            if (Distance(next, to) < 60f || steps > 55)
            {
                shellTimer?.Kill();
                var blast = Copy(to);
                if (shell.IsValid) shell.Remove();
                ExplosionFx(blast, 320, 120);
            }
        }, TimerFlags.REPEAT | TimerFlags.STOP_ON_MAPCHANGE);
    }


    private void OrbitingC4(int token)
    {
        var carrier = Pick(AlivePlayers());
        var pawn = carrier is null ? null : Pawn(carrier);
        if (carrier is null || pawn?.AbsOrigin is null) return;

        var bombs = new List<CDynamicProp>();
        for (var i = 0; i < 5; i++)
        {
            var bomb = SpawnDynamic(C4Model, Copy(pawn.AbsOrigin));
            if (bomb is not null) bombs.Add(bomb);
        }

        carrier.PrintToCenter("ВОКРУГ ТЕБЯ 5 БОМБ. ЭТО ДЕКОР. НАВЕРНОЕ.");
        Broadcast($"[СОЛЬ] вокруг {carrier.PlayerName} теперь орбитальная группировка C4.");

        var tick = 0;
        CounterStrikeSharp.API.Modules.Timers.Timer? timer = null;
        timer = AddTimer(0.06f, () =>
        {
            if (token != _roundToken || tick++ > 280)
            {
                timer?.Kill();
                return;
            }

            var live = AlivePlayers().FirstOrDefault(p => p.Slot == carrier.Slot);
            var p = live is null ? null : Pawn(live);
            if (p?.AbsOrigin is null)
            {
                timer?.Kill();
                return;
            }

            for (var i = 0; i < bombs.Count; i++)
            {
                var bomb = bombs[i];
                if (!bomb.IsValid) continue;

                var phase = tick * 0.09f + i * (MathF.PI * 2f / bombs.Count);
                bomb.Teleport(new Vector(
                    p.AbsOrigin.X + MathF.Cos(phase) * 110f,
                    p.AbsOrigin.Y + MathF.Sin(phase) * 110f,
                    p.AbsOrigin.Z + 45f + MathF.Sin(phase * 2f) * 25f),
                    new QAngle(0, tick * 5 % 360, 0),
                    null);
            }
        }, TimerFlags.REPEAT | TimerFlags.STOP_ON_MAPCHANGE);
    }
}
