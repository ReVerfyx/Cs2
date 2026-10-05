using System.Text.Json.Serialization;
using CounterStrikeSharp.API.Core;

namespace Sol;

public sealed class SolConfig : BasePluginConfig
{
    [JsonPropertyName("Enabled")]
    public bool Enabled { get; set; } = true;

    [JsonPropertyName("AnomaliesPerRoundMin")]
    public int AnomaliesPerRoundMin { get; set; } = 2;

    [JsonPropertyName("AnomaliesPerRoundMax")]
    public int AnomaliesPerRoundMax { get; set; } = 4;

    [JsonPropertyName("AlwaysGiveOneFlyerPerTeam")]
    public bool AlwaysGiveOneFlyerPerTeam { get; set; } = true;

    [JsonPropertyName("AirPlantHeight")]
    public float AirPlantHeight { get; set; } = 500f;

    [JsonPropertyName("AirPlantRadius")]
    public float AirPlantRadius { get; set; } = 150f;

    [JsonPropertyName("AirPlantHoldSeconds")]
    public float AirPlantHoldSeconds { get; set; } = 6f;

    [JsonPropertyName("PvoIntervalSeconds")]
    public float PvoIntervalSeconds { get; set; } = 10f;

    [JsonPropertyName("DroneIntervalSeconds")]
    public float DroneIntervalSeconds { get; set; } = 12f;

    [JsonPropertyName("EnableMapRotation")]
    public bool EnableMapRotation { get; set; } = true;

    [JsonPropertyName("RoundsPerMap")]
    public int RoundsPerMap { get; set; } = 6;

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
