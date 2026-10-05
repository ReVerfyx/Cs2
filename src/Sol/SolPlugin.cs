using CounterStrikeSharp.API;
using CounterStrikeSharp.API.Core;
using CounterStrikeSharp.API.Core.Attributes.Registration;
using CounterStrikeSharp.API.Modules.Commands;
using CounterStrikeSharp.API.Modules.Config;
using CounterStrikeSharp.API.Modules.Entities.Constants;
using CounterStrikeSharp.API.Modules.Timers;
using CounterStrikeSharp.API.Modules.Utils;
using Microsoft.Extensions.Logging;

namespace Sol;

public sealed partial class SolPlugin : BasePlugin, IPluginConfig<SolConfig>
{
    public override string ModuleName => "Соль";
    public override string ModuleVersion => "0.2.0";
    public override string ModuleAuthor => "ReVerfyx";
    public override string ModuleDescription => "Абсурдный chaos-mode для CS2.";

    public SolConfig Config { get; set; } = new();

    private readonly Random _random = new();
    private readonly Dictionary<uint, Vector> _bombTargetOrigins = new();

    private int _roundToken;
    private int _roundsOnMap;
    private int? _buddyBombSlot;
    private Vector? _airPlantPoint;
    private float _airPlantHeld;
    private string _lastChaos = "ещё ничего";

    public void OnConfigParsed(SolConfig config)
    {
        config.AnomaliesPerRoundMin = Math.Clamp(config.AnomaliesPerRoundMin, 1, 8);
        config.AnomaliesPerRoundMax = Math.Clamp(config.AnomaliesPerRoundMax, config.AnomaliesPerRoundMin, 10);
        config.RoundsPerMap = Math.Max(1, config.RoundsPerMap);
        config.AirPlantHeight = Math.Clamp(config.AirPlantHeight, 150f, 1800f);
        config.AirPlantRadius = Math.Clamp(config.AirPlantRadius, 60f, 500f);
        config.AirPlantHoldSeconds = Math.Clamp(config.AirPlantHoldSeconds, 2f, 20f);
        config.PvoIntervalSeconds = Math.Max(4f, config.PvoIntervalSeconds);
        config.DroneIntervalSeconds = Math.Max(4f, config.DroneIntervalSeconds);
        config.MeteorIntervalSeconds = Math.Max(1.5f, config.MeteorIntervalSeconds);
        config.ChickenRainCount = Math.Clamp(config.ChickenRainCount, 1, 40);
        Config = config;
    }

    public override void Load(bool hotReload)
    {
        if (!Config.Enabled)
        {
            Logger.LogWarning("Соль отключена в конфиге.");
            return;
        }

        RegisterChaosResources();
        Logger.LogInformation("Соль загружена. Визуальный режим хаоса готов.");
    }

    public override void Unload(bool hotReload)
    {
        _roundToken++;
        CleanupChaosEntities();
        RestoreBombTargets();
    }

    [GameEventHandler]
    public HookResult OnRoundStart(EventRoundStart @event, GameEventInfo info)
    {
        if (!Config.Enabled) return HookResult.Continue;

        _roundToken++;
        _roundsOnMap++;
        ResetRoundState();

        var token = _roundToken;
        AddTimer(1.0f, () =>
        {
            if (token != _roundToken) return;
            StartChaos(token);
        }, TimerFlags.STOP_ON_MAPCHANGE);

        return HookResult.Continue;
    }

    [GameEventHandler]
    public HookResult OnRoundEnd(EventRoundEnd @event, GameEventInfo info)
    {
        if (!Config.Enabled) return HookResult.Continue;

        _roundToken++;
        CleanupChaosEntities();
        RestoreBombTargets();
        Server.ExecuteCommand("mp_ignore_round_win_conditions 0");

        if (Config.EnableMapRotation && _roundsOnMap >= Config.RoundsPerMap)
        {
            _roundsOnMap = 0;
            AddTimer(3.0f, ChangeRandomMap, TimerFlags.STOP_ON_MAPCHANGE);
        }

        return HookResult.Continue;
    }

    [ConsoleCommand("css_salt", "Принудительно запускает ещё одну волну хаоса.")]
    public void ForceChaos(CCSPlayerController? player, CommandInfo command)
    {
        if (!Config.Enabled)
        {
            command.ReplyToCommand("[Соль] Плагин выключен.");
            return;
        }

        StartChaos(_roundToken);
        command.ReplyToCommand("[Соль] Ещё соли добавлено.");
    }

    [ConsoleCommand("css_salt_status", "Показывает активный набор хаоса.")]
    public void ChaosStatus(CCSPlayerController? player, CommandInfo command)
    {
        command.ReplyToCommand($"[Соль] Сейчас: {_lastChaos}");
    }

    [ConsoleCommand("css_salt_map", "Сразу выбирает случайную карту из MapPool.")]
    public void ChaosMap(CCSPlayerController? player, CommandInfo command)
    {
        ChangeRandomMap();
    }

    private void StartChaos(int token)
    {
        if (token != _roundToken) return;

        if (Config.AlwaysGiveOneFlyerPerTeam)
            GiveRandomFlyerToEachTeam();

        var anomalies = new List<(string Name, Action<int> Run)>
        {
            ("ЛУНА", _ => Gravity(260)),
            ("ЮПИТЕР", _ => Gravity(1450)),
            ("ЛЁД ПОД НОГАМИ", _ => Server.ExecuteCommand("sv_friction 0.8")),
            ("БЕСКОНЕЧНЫЕ ПАТРОНЫ", _ => Server.ExecuteCommand("sv_infinite_ammo 1")),
            ("FRIENDLY FIRE", _ => Server.ExecuteCommand("mp_friendlyfire 1")),
            ("ТОЛЬКО В ГОЛОВУ", _ => Server.ExecuteCommand("mp_damage_headshot_only 1")),
            ("HP-РУЛЕТКА", _ => HealthLottery()),
            ("РУЛЕТКА ОРУЖИЯ", _ => WeaponRoulette()),
            ("ZEUS-АД", _ => WeaponMode("weapon_taser")),
            ("NEGEV-АД", _ => WeaponMode("weapon_negev")),
            ("DEAGLE-АД", _ => WeaponMode("weapon_deagle")),
            ("НОЖИ И ПАНИКА", _ => WeaponMode("weapon_knife")),
            ("ПЕРЕМЕШАТЬ ВСЕХ", _ => ShufflePlayers()),
            ("ВСЕ В НЕБО", _ => LaunchEveryone()),
            ("РАКЕТНЫЕ БОТИНКИ", RocketShoes),
            ("ТАНКИ", _ => TankMode()),
            ("ДРОНЫ-КАМИКАДЗЕ", DroneSwarm),
            ("ПВО", PvoMode),
            ("МЕТЕОРИТНЫЙ ДОЖДЬ", MeteorRain),
            ("ДОЖДЬ ИЗ КУР", ChickenRain),
            ("ЧЁРНАЯ ДЫРА", BlackHole),
            ("МЕБЕЛЬНЫЙ ТОРНАДО", FurnitureTornado),
            ("СТЕНА ИЗ СЕЙФОВ", WallOfSafes),
            ("ОРБИТАЛЬНЫЕ C4", OrbitingC4),
            ("ТИММЕЙТ = БОМБА + ПЛЭНТ В ВОЗДУХЕ", BuddyBombMode),
            ("НАСТОЯЩИЙ БОМБСАЙТ В ВОЗДУХЕ", _ => LiftBombSites()),
            ("C4 НА 10 СЕКУНД", _ => Server.ExecuteCommand("mp_c4timer 10")),
            ("C4 НА ВЕЧНОСТЬ", _ => Server.ExecuteCommand("mp_c4timer 90"))
        };

        var count = _random.Next(Config.AnomaliesPerRoundMin, Config.AnomaliesPerRoundMax + 1);
        var picked = anomalies
            .OrderBy(_ => _random.Next())
            .Take(Math.Min(count, anomalies.Count))
            .ToList();

        _lastChaos = string.Join(" + ", picked.Select(x => x.Name));
        Broadcast($"[СОЛЬ] {_lastChaos}");

        foreach (var anomaly in picked)
        {
            try
            {
                anomaly.Run(token);
            }
            catch (Exception ex)
            {
                Logger.LogError(ex, "Ошибка аномалии {Anomaly}", anomaly.Name);
            }
        }
    }

    private void ResetRoundState()
    {
        CleanupChaosEntities();
        _buddyBombSlot = null;
        _airPlantPoint = null;
        _airPlantHeld = 0f;

        RestoreBombTargets();

        Server.ExecuteCommand("sv_gravity 800");
        Server.ExecuteCommand("sv_friction 5.2");
        Server.ExecuteCommand("sv_infinite_ammo 0");
        Server.ExecuteCommand("mp_friendlyfire 0");
        Server.ExecuteCommand("mp_damage_headshot_only 0");
        Server.ExecuteCommand("mp_c4timer 40");
        Server.ExecuteCommand("mp_ignore_round_win_conditions 0");

        foreach (var player in AlivePlayers())
        {
            var pawn = Pawn(player);
            if (pawn is null) continue;

            pawn.MoveType = MoveType_t.MOVETYPE_WALK;
            pawn.ActualMoveType = MoveType_t.MOVETYPE_WALK;
            Utilities.SetStateChanged(pawn, "CBaseEntity", "m_MoveType");
        }
    }

    private void GiveRandomFlyerToEachTeam()
    {
        foreach (var team in new[] { CsTeam.Terrorist, CsTeam.CounterTerrorist })
        {
            var player = Pick(AlivePlayers(team));
            if (player is null) continue;

            SetFlying(player, true);
            player.PrintToCenter("ТЫ ЛЕТАЕШЬ. НЕ СПРАШИВАЙ ПОЧЕМУ.");
            Broadcast($"[СОЛЬ] {player.PlayerName} теперь авиация команды.");
        }
    }

    private void Gravity(int value)
    {
        Server.ExecuteCommand($"sv_gravity {value}");
    }

    private void HealthLottery()
    {
        foreach (var player in AlivePlayers())
        {
            var pawn = Pawn(player);
            if (pawn is null) continue;

            var hp = _random.Next(20, 301);
            pawn.MaxHealth = hp;
            pawn.Health = hp;
            Utilities.SetStateChanged(pawn, "CBaseEntity", "m_iHealth");
            player.PrintToCenter($"ТЕБЕ ВЫПАЛО {hp} HP");
        }
    }

    private void WeaponRoulette()
    {
        string[] weapons =
        [
            "weapon_awp",
            "weapon_ssg08",
            "weapon_deagle",
            "weapon_negev",
            "weapon_nova",
            "weapon_taser",
            "weapon_m249",
            "weapon_bizon",
            "weapon_revolver",
            "weapon_knife"
        ];

        foreach (var player in AlivePlayers())
        {
            var weapon = weapons[_random.Next(weapons.Length)];
            GiveOnly(player, weapon);
        }
    }

    private void WeaponMode(string weapon)
    {
        foreach (var player in AlivePlayers())
            GiveOnly(player, weapon);
    }

    private void GiveOnly(CCSPlayerController player, string weapon)
    {
        try
        {
            player.RemoveWeapons();
            player.GiveNamedItem("weapon_knife");

            if (!string.Equals(weapon, "weapon_knife", StringComparison.OrdinalIgnoreCase))
                player.GiveNamedItem(weapon);
        }
        catch (Exception ex)
        {
            Logger.LogWarning(ex, "Не удалось выдать {Weapon} игроку {Player}", weapon, player.PlayerName);
        }
    }

    private void ShufflePlayers()
    {
        var players = AlivePlayers();
        var positions = players
            .Select(Pawn)
            .Where(x => x?.AbsOrigin is not null)
            .Select(x => Copy(x!.AbsOrigin!))
            .OrderBy(_ => _random.Next())
            .ToList();

        if (positions.Count != players.Count) return;

        for (var i = 0; i < players.Count; i++)
        {
            var pawn = Pawn(players[i]);
            if (pawn is null) continue;
            pawn.Teleport(positions[i], pawn.AbsRotation, null);
        }

        Broadcast("[СОЛЬ] Координаты игроков перемешаны.");
    }

    private void LaunchEveryone()
    {
        foreach (var player in AlivePlayers())
        {
            var pawn = Pawn(player);
            if (pawn is null) continue;

            var velocity = new Vector(
                _random.Next(-250, 251),
                _random.Next(-250, 251),
                _random.Next(650, 1001));

            pawn.Teleport(null, null, velocity);
        }
    }

    private void RocketShoes(int token)
    {
        CounterStrikeSharp.API.Modules.Timers.Timer? timer = null;
        timer = AddTimer(5.0f, () =>
        {
            if (token != _roundToken)
            {
                timer?.Kill();
                return;
            }

            var player = Pick(AlivePlayers());
            var pawn = player is null ? null : Pawn(player);
            if (player is null || pawn is null) return;

            pawn.Teleport(null, null, new Vector(
                _random.Next(-350, 351),
                _random.Next(-350, 351),
                _random.Next(700, 1201)));

            player.PrintToCenter("РАКЕТНЫЕ БОТИНКИ СРАБОТАЛИ");
        }, TimerFlags.REPEAT | TimerFlags.STOP_ON_MAPCHANGE);
    }

    private void TankMode()
    {
        foreach (var team in new[] { CsTeam.Terrorist, CsTeam.CounterTerrorist })
        {
            var tank = Pick(AlivePlayers(team));
            var pawn = tank is null ? null : Pawn(tank);
            if (tank is null || pawn is null) continue;

            pawn.MaxHealth = 350;
            pawn.Health = 350;
            pawn.ArmorValue = 100;

            Utilities.SetStateChanged(pawn, "CBaseEntity", "m_iHealth");
            Utilities.SetStateChanged(pawn, "CCSPlayerPawn", "m_ArmorValue");

            try
            {
                tank.GiveNamedItem("weapon_negev");
            }
            catch
            {
                // Оружие не критично: роль танка всё равно работает по HP/броне.
            }

            AddTankVisual(tank, _roundToken);
            tank.PrintToCenter("ТЫ ТАНК: 350 HP + 100 ARMOR");
            Broadcast($"[СОЛЬ] {tank.PlayerName} назначен танком. Теперь ещё и выглядит подозрительно.");
        }
    }

    private void BuddyBombMode(int token)
    {
        var buddy = Pick(AlivePlayers(CsTeam.Terrorist));
        var pawn = buddy is null ? null : Pawn(buddy);

        if (buddy is null || pawn?.AbsOrigin is null)
        {
            Broadcast("[СОЛЬ] Для живой бомбы не хватило террористов.");
            return;
        }

        _buddyBombSlot = buddy.Slot;
        _airPlantPoint = new Vector(
            pawn.AbsOrigin.X + _random.Next(-250, 251),
            pawn.AbsOrigin.Y + _random.Next(-250, 251),
            pawn.AbsOrigin.Z + Config.AirPlantHeight);
        _airPlantHeld = 0f;

        pawn.MaxHealth = Math.Max(pawn.MaxHealth, 250);
        pawn.Health = Math.Max(pawn.Health, 250);
        Utilities.SetStateChanged(pawn, "CBaseEntity", "m_iHealth");

        SetFlying(buddy, true);
        SpawnAirPlantVisual();
        Server.ExecuteCommand("mp_ignore_round_win_conditions 1");

        Broadcast($"[СОЛЬ] БОМБА = {buddy.PlayerName}. Он должен зависнуть в воздушном плэнте {Config.AirPlantHoldSeconds:0.#} сек.");

        CounterStrikeSharp.API.Modules.Timers.Timer? timer = null;
        timer = AddTimer(0.5f, () =>
        {
            if (token != _roundToken)
            {
                timer?.Kill();
                return;
            }

            var liveBuddy = AlivePlayers(CsTeam.Terrorist).FirstOrDefault(p => p.Slot == _buddyBombSlot);
            var livePawn = liveBuddy is null ? null : Pawn(liveBuddy);

            if (liveBuddy is null || livePawn?.AbsOrigin is null || _airPlantPoint is null)
            {
                timer?.Kill();
                Broadcast("[СОЛЬ] Живая бомба уничтожена. CT победили.");
                EndChaosRound(RoundEndReason.CTsWin);
                return;
            }

            var distance = Distance(livePawn.AbsOrigin, _airPlantPoint);
            liveBuddy.PrintToCenter($"ЖИВАЯ БОМБА\nдо воздушного плэнта: {distance:0} ед.\nудержание: {_airPlantHeld:0.0}/{Config.AirPlantHoldSeconds:0.0}");

            if (distance <= Config.AirPlantRadius)
            {
                _airPlantHeld += 0.5f;

                if (_airPlantHeld >= Config.AirPlantHoldSeconds)
                {
                    timer?.Kill();
                    Broadcast("[СОЛЬ] ЖИВАЯ БОМБА ЗАПЛЭНТИЛАСЬ В ВОЗДУХЕ. T победили.");
                    EndChaosRound(RoundEndReason.TerroristsWin);
                }
            }
            else
            {
                _airPlantHeld = 0f;
            }
        }, TimerFlags.REPEAT | TimerFlags.STOP_ON_MAPCHANGE);
    }

    private void LiftBombSites()
    {
        var targets = Utilities.FindAllEntitiesByDesignerName<CBombTarget>("func_bomb_target").ToList();
        if (targets.Count == 0)
        {
            Broadcast("[СОЛЬ] На этой карте нет обычных bombsite-триггеров.");
            return;
        }

        foreach (var target in targets)
        {
            if (!target.IsValid || target.AbsOrigin is null) continue;

            if (!_bombTargetOrigins.ContainsKey(target.Index))
                _bombTargetOrigins[target.Index] = Copy(target.AbsOrigin);

            target.Teleport(
                new Vector(target.AbsOrigin.X, target.AbsOrigin.Y, target.AbsOrigin.Z + Config.AirPlantHeight),
                target.AbsRotation,
                null);
        }

        Broadcast("[СОЛЬ] A/B уехали в воздух. Хорошей посадки.");
    }

    private void RestoreBombTargets()
    {
        if (_bombTargetOrigins.Count == 0) return;

        foreach (var target in Utilities.FindAllEntitiesByDesignerName<CBombTarget>("func_bomb_target"))
        {
            if (!target.IsValid) continue;
            if (_bombTargetOrigins.TryGetValue(target.Index, out var origin))
                target.Teleport(origin, target.AbsRotation, null);
        }

        _bombTargetOrigins.Clear();
    }

    private void PvoMode(int token)
    {
        Broadcast("[СОЛЬ] ПВО включено. Летающие игроки теперь официально цели.");

        CounterStrikeSharp.API.Modules.Timers.Timer? timer = null;
        timer = AddTimer(Config.PvoIntervalSeconds, () =>
        {
            if (token != _roundToken)
            {
                timer?.Kill();
                return;
            }

            var aerial = AlivePlayers()
                .Where(p => Pawn(p)?.MoveType == MoveType_t.MOVETYPE_NOCLIP)
                .ToList();

            var target = Pick(aerial);
            if (target is null) return;

            target.PrintToCenter("ПВО: ЗАХВАТ ЦЕЛИ\n2 СЕКУНДЫ ДО УДАРА");
            Broadcast($"[ПВО] захвачена цель: {target.PlayerName}");

            if (Config.EnableVisualEntities)
            {
                LaunchVisualMissile(target, token);
            }
            else
            {
                AddTimer(2.0f, () =>
                {
                    if (token != _roundToken) return;
                    var stillAlive = AlivePlayers().FirstOrDefault(p => p.Slot == target.Slot);
                    if (stillAlive is null) return;
                    Damage(stillAlive, 140, explode: true);
                }, TimerFlags.STOP_ON_MAPCHANGE);
            }
        }, TimerFlags.REPEAT | TimerFlags.STOP_ON_MAPCHANGE);
    }

    private void DroneSwarm(int token)
    {
        Broadcast("[СОЛЬ] В небе дроны-камикадзе. Иногда они выбирают кого-то просто потому что могут.");

        CounterStrikeSharp.API.Modules.Timers.Timer? timer = null;
        timer = AddTimer(Config.DroneIntervalSeconds, () =>
        {
            if (token != _roundToken)
            {
                timer?.Kill();
                return;
            }

            var target = Pick(AlivePlayers());
            if (target is null) return;

            var slot = target.Slot;
            target.PrintToCenter("ДРОН ВЫБРАЛ ТЕБЯ\n3... 2... 1...");
            Broadcast($"[ДРОН] летит к {target.PlayerName}");

            if (Config.EnableVisualEntities)
            {
                LaunchVisualDrone(target, token);
            }
            else
            {
                AddTimer(3.0f, () =>
                {
                    if (token != _roundToken) return;

                    var live = AlivePlayers().FirstOrDefault(p => p.Slot == slot);
                    var pawn = live is null ? null : Pawn(live);
                    if (live is null || pawn is null) return;

                    pawn.Teleport(null, null, new Vector(
                        _random.Next(-450, 451),
                        _random.Next(-450, 451),
                        _random.Next(450, 801)));

                    Damage(live, 85, explode: true);
                }, TimerFlags.STOP_ON_MAPCHANGE);
            }
        }, TimerFlags.REPEAT | TimerFlags.STOP_ON_MAPCHANGE);
    }

    private void Damage(CCSPlayerController player, int amount, bool explode)
    {
        var pawn = Pawn(player);
        if (pawn is null) return;

        if (pawn.Health <= amount)
        {
            player.CommitSuicide(explode, true);
            return;
        }

        pawn.Health -= amount;
        Utilities.SetStateChanged(pawn, "CBaseEntity", "m_iHealth");
    }

    private void SetFlying(CCSPlayerController player, bool flying)
    {
        var pawn = Pawn(player);
        if (pawn is null) return;

        pawn.MoveType = flying ? MoveType_t.MOVETYPE_NOCLIP : MoveType_t.MOVETYPE_WALK;
        pawn.ActualMoveType = flying ? MoveType_t.MOVETYPE_OBSERVER : MoveType_t.MOVETYPE_WALK;
        Utilities.SetStateChanged(pawn, "CBaseEntity", "m_MoveType");
    }

    private void EndChaosRound(RoundEndReason reason)
    {
        var proxy = Utilities.FindAllEntitiesByDesignerName<CCSGameRulesProxy>("cs_gamerules").FirstOrDefault();
        if (proxy?.GameRules is not null)
        {
            proxy.GameRules.TerminateRound(1.0f, reason);
            return;
        }

        Server.ExecuteCommand("mp_restartgame 1");
    }

    private void ChangeRandomMap()
    {
        var candidates = Config.MapPool
            .Where(x => !string.IsNullOrWhiteSpace(x))
            .Where(Server.IsMapValid)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();

        if (candidates.Count == 0)
        {
            Broadcast("[СОЛЬ] MapPool пустой или карты не установлены.");
            return;
        }

        var map = candidates[_random.Next(candidates.Count)];
        Broadcast($"[СОЛЬ] Следующая странность: {map}");
        Server.ExecuteCommand($"changelevel \"{map}\"");
    }

    private List<CCSPlayerController> AlivePlayers(CsTeam? team = null)
    {
        return Utilities.GetPlayers()
            .Where(p => p is { IsValid: true, PawnIsAlive: true })
            .Where(p => team is null || p.Team == team.Value)
            .ToList();
    }

    private static CCSPlayerPawn? Pawn(CCSPlayerController player)
    {
        var pawn = player.PlayerPawn.Value;
        return pawn is { IsValid: true } ? pawn : null;
    }

    private T? Pick<T>(IReadOnlyList<T> list) where T : class
    {
        return list.Count == 0 ? null : list[_random.Next(list.Count)];
    }

    private static Vector Copy(Vector source) => new(source.X, source.Y, source.Z);

    private static float Distance(Vector a, Vector b)
    {
        var x = a.X - b.X;
        var y = a.Y - b.Y;
        var z = a.Z - b.Z;
        return MathF.Sqrt(x * x + y * y + z * z);
    }

    private static void Broadcast(string message)
    {
        foreach (var player in Utilities.GetPlayers().Where(p => p is { IsValid: true }))
            player.PrintToChat(message);
    }
}
