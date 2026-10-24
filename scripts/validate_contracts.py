"""Validate pinned contracts against actual integration-test HTTP bodies and envelopes."""
import json
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker
from openapi_spec_validator import validate
ROOT = Path(__file__).resolve().parents[1]
api = json.loads((ROOT/'src/main/resources/contracts/spatial-service.openapi.json').read_text())
validate(api)
for name, schema_name in {'feature':'Feature','layer':'Layer','asset':'Asset','import':'ImportJob','query':'QueryResult','export':'GeoCollection','bbox':'Bounds'}.items():
    document = dict(api)
    document['$ref'] = '#/components/schemas/' + schema_name
    Draft202012Validator(document,format_checker=FormatChecker()).validate(json.loads((ROOT/'target/api-contract-samples'/f'{name}.json').read_text()))
count=0
for file in (ROOT/'target/event-contract-samples').glob('*.json'):
    event=json.loads(file.read_text())
    schema=json.loads((ROOT/'src/main/resources/contracts/events/spatial/v1'/f'{event["eventType"]}.schema.json').read_text())
    Draft202012Validator(schema,format_checker=FormatChecker()).validate(event)
    count+=1
assert count > 0, 'Run integration tests first; actual envelopes are required'
for event in json.loads((ROOT/'examples/synthetic-estate-events.json').read_text()):
    schema=json.loads((ROOT/'src/main/resources/contracts/events/v2'/f'{event["eventType"]}.schema.json').read_text())
    Draft202012Validator(schema,format_checker=FormatChecker()).validate(event)
print(f'PASS: seven actual HTTP response shapes, {count} actual spatial events, eleven synthetic estate snapshots, pinned OpenAPI')

request_doc=dict(api)
request_doc['$ref']='#/components/schemas/ImportInput'
Draft202012Validator(request_doc,format_checker=FormatChecker()).validate({'estateId':'00000000-0000-0000-0000-000000000001','sourceFilename':'synthetic-estate.geojson','geojson':json.loads((ROOT/'examples/synthetic-estate.geojson').read_text())})
runtime=json.loads((ROOT/'target/openapi-runtime.json').read_text())
validate(runtime)
assert set(runtime['paths']) == set(api['paths'])
print('PASS: sample import request and runtime OpenAPI paths/schemas')
