"""Explicit, bounded public market-data contract; never forward account fields."""
import math

N = 'number'
S = 'string'
B = 'boolean'
T = 'timestamp'
def fields(names, kind):
    return dict.fromkeys(names.split(), kind)
metric = {**fields('value compare sectorAverage yoy', N), **fields('replaceText direction sectorPercentile', S)}
price_range = fields('low high current', N)
share = fields('call put', N)
window = {'available': B, **fields('ratioPct baselineCumVolume', N)}
SCHEMAS = {
    'header': {**fields('marketStatus changeBasis', S), **fields('asOf', T), **fields('nextPollAfterMs price changePercent turnover marketCap marketCapRank turnoverRank', N), 'dayRange': price_range, 'week52Range': price_range, 'extendedHours': {**fields('price changePercent', N), **fields('asOf', T), **fields('session marketStatus', S)}},
    'key_metrics': {**dict.fromkeys('per eps revenueTtm dividendYield roe shortInterestPct daysToCover'.split(), metric), **fields('shortAsOf shortBasis asOf periodLabel', S)},
    'revenue': {'source': S, 'quarters': [{**fields('label replaceText direction', S), **fields('revenue yoy', N)}]},
    'analyst': {**fields('analystCount upsidePct', N), **fields('label provider asOf', S), 'dist': fields('buy hold sell', N), 'target': fields('mean low high', N), 'recent': [{**fields('firm firmKo rating prevRating action at prevSource', S), **fields('target prevTarget upsidePct', N), 'isNew': B}]},
    'insider': {**fields('window buyCount sellCount netValue', N), **fields('label asOf', S), 'recent': [{**fields('name title transactionDate transactionCode', S), 'value': N}]},
    'options': {**fields('symbol asOf snapshotDate snapshotUpdatedAt batchDate batchUpdatedAt nearestExpiry', S), **fields('optionable snapshotIsPriorDay batchIsPriorDay batchIsProvisional', B), **fields('daysToExpiry maxPain volume putCallRatioVolume putCallRatioOpenInterest vsAvg3d vsAvg7d vsAvg30d referencePrice netGammaExposure gammaPer1Pct callWall putWall gammaFlip nextPollAfterMs', N), 'volumeShare': share, 'openInterestShare': share, 'premiumShare': share, 'optionVolumeVsAvg': {**fields('roundSeq asOfMinute currentCumVolume', N), 'windows': dict.fromkeys(('d3', 'd7', 'd30'), window)}},
    'news': {'items': [fields('title publisher link published_at', S)]},
}


def clean(value, schema):
    if value is None:
        return None
    if isinstance(schema, dict):
        if not isinstance(value, dict):
            raise ValueError('Invalid container')
        return {k: clean(v, schema[k]) for k, v in value.items() if k in schema}
    if isinstance(schema, list):
        if not isinstance(value, list) or len(value) > 1000:
            raise ValueError('Invalid rows')
        return [clean(row, schema[0]) for row in value]
    if schema in (N, T) and type(value) in (int, float) and math.isfinite(value) and abs(value) <= 1e20:
        return value
    if schema in (S, T) and isinstance(value, str) and len(value) <= 4096:
        return value
    if schema == B and type(value) is bool:
        return value
    raise ValueError('Invalid field')


def normalize_section(section, value):
    return clean(value, SCHEMAS[section])
