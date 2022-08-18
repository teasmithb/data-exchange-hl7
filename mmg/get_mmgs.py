import json
import requests

base_url = 'https://mmgat.services.cdc.gov/'
list_all = 'api/guide/all?type=0'
write_location = './'

# Get list of all MMGs. Have to use verify=False because the site has a self-signed certificate
response = requests.get(base_url + list_all, verify=False)
results = response.json().get('result')

# define function to filter results by guideStatus
def keep_in_list(result):
    return result.get('guideStatus', '') == 'Final'

# use the function to filter the results
filtered_mmgs = list(filter(keep_in_list, results))

# get details of each mmg in the filtered list
count = 0
for mmg in filtered_mmgs:
    count += 1
    id = mmg.get('id')
    mmg_url = f'api/guide/{id}'
    resp = requests.get(base_url + mmg_url, verify=False)
    result = resp.json().get('result')
    # this is where we would write the MMG to a file.
    # uncomment to print to console
    # print(json.dumps(result, indent=2))
    name = result.get('name', count) + ".json"
    with open(write_location + name, 'w') as f:
        f.write(json.dumps(result, indent=2))

print(f'Pulled {count} MMGs. Script complete.')