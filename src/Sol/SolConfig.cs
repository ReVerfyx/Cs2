using System.Text.Json.Serialization;
using CounterStrikeSharp.API.Core;

namespace Sol;

public sealed class SolConfig : BasePluginConfig
{
    [JsonPropertyName("Enabled")]
    public bool Enabled { get; set; } = true;

    [JsonPropertyName("AnomaliesPerRoundMin")]
    public int AnomaliesPerRoundMin { get; set; } = 3;

    [JsonPropertyName("AnomaliesPerRoundMax")]
    public int AnomaliesPerRoundMax { get; set; } = 6;

    [JsonPropertyName("AlwaysGiveOneFlyerPerTeam")]
    public bool AlwaysGiveOneFlyerPerTeam { get; set; } = true;

    [JsonPropertyName("EnableVisualEntities")]
    public bool EnableVisualEntities { get; set; } = true;

    [JsonPropertyName("AirPlantHeight")]
    public float AirPlantHeight { get; set; } = 500f;

    [JsonPropertyName("AirPlantRadius")]
    public float AirPlantRadius { get; set; } = 150f;

    [JsonPropertyName("AirPlantHoldSeconds")]
    public float AirPlantHoldSeconds { get; set; } = 6f;

    [JsonPropertyName("PvoIntervalSeconds")]
    public float PvoIntervalSeconds { get; set; } = 10f;

    [JsonPropertyName("DroneIntervalSeconds")]
    public float DroneIntervalSeconds { get; set; } = 11f;

    [JsonPropertyName("MeteorIntervalSeconds")]
    public float MeteorIntervalSeconds { get; set; } = 4f;

    [JsonPropertyName("ChickenRainCount")]
    public int ChickenRainCount { get; set; } = 12;

    [JsonPropertyName("EnableMapRotation")]
    public bool EnableMapRotation { get; set; } = true;

    [JsonPropertyName("RoundsPerMap")]
    public int RoundsPerMap { get; set; } = 6;

    // Stock CS2 resources. They are configurable so a server owner can swap
    // them for workshop/custom assets later without rebuilding the plugin.
    [JsonPropertyName("DroneModel")]
    public string DroneModel { get; set; } = "models/props_survival/dronegun.vmdl";

    [JsonPropertyName("TankBodyModel")]
    public string TankBodyModel { get; set; } = "models/props_survival/safe/safe.vmdl";

    [JsonPropertyName("TankTurretModel")]
    public string TankTurretModel { get; set; } = "models/props_survival/dronegun.vmdl";

    [JsonPropertyName("MissileModel")]
    public string MissileModel { get; set; } = "weapons/models/grenade/hegrenade/weapon_hegrenade.vmdl";

    [JsonPropertyName("MeteorModel")]
    public string MeteorModel { get; set; } = "models/props/coop_cementplant/exloding_barrel/exploding_barrel.vmdl";

    [JsonPropertyName("AirPlantModel")]
    public string AirPlantModel { get; set; } = "models/props_survival/safe/safe.vmdl";

    [JsonPropertyName("MapPool")]
    public List<string> MapPool { get; set; } =
    [
        "de_nuke",
        "de_vertigo",
        "de_ancient",
        "de_anubis",
        "de_inferno",
        "cs_office"
    ];
}
