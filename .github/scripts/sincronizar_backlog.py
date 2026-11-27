"""Atualiza apenas os cartões do BuddyUp. Por padrão, mostra uma prévia."""
import json
import os
import subprocess

FIELDS = """
    id isArchived
    fieldValueByName(name: "Status") { ... on ProjectV2ItemFieldSingleSelectValue { name } }
    content { ... on Issue {
        number state stateReason repository { nameWithOwner }
        labels(first: 50) { pageInfo { hasNextPage } nodes { name } }
        blockedBy(first: 50) { pageInfo { hasNextPage } nodes { state stateReason } }
    } }
"""
QUERY = """
query($after:String) {
  user(login:"lucasccesar") { projectV2(number:1) {
    id title
    field(name:"Status") { ... on ProjectV2SingleSelectField { id options { id name } } }
    items(first:20, after:$after) {
      pageInfo { hasNextPage endCursor }
      nodes { """ + FIELDS + """ }
    }
  } }
}
"""
RECHECK = 'query($id:ID!) { node(id:$id) { ... on ProjectV2Item { ' + FIELDS + ' } } }'
UPDATE = """
mutation($input:UpdateProjectV2ItemFieldValueInput!) {
  updateProjectV2ItemFieldValue(input:$input) { projectV2Item { id } }
}
"""


def graphql(query, **variables):
    payload = json.dumps({"query": query, "variables": variables})
    response = subprocess.run(["gh", "api", "graphql", "--input", "-"],
                              input=payload, encoding="utf-8", capture_output=True)
    if response.returncode:
        raise RuntimeError(response.stderr.strip() or response.stdout.strip())
    envelope = json.loads(response.stdout)
    if envelope.get("errors"):
        raise RuntimeError(str(envelope["errors"]))
    return envelope["data"]


def completed(issue):
    return issue["state"] == "CLOSED" and issue["stateReason"] == "COMPLETED"


def next_status(item):
    issue = item.get("content") or {}
    status = (item.get("fieldValueByName") or {}).get("name")
    if item["isArchived"] or issue.get("repository", {}).get("nameWithOwner") != "lucasccesar/BuddyUp":
        return None
    if completed(issue):
        return "Concluída" if status != "Concluída" else None
    if issue["state"] != "OPEN":
        return None
    if any(issue[key]["pageInfo"]["hasNextPage"] for key in ("labels", "blockedBy")):
        print(f"Aviso: consulta incompleta para #{issue['number']}. Estado preservado.")
        return None
    labels = {label["name"].casefold() for label in issue["labels"]["nodes"]}
    if "épico" in labels:
        return None
    pending = bool({"aguarda-decisão", "aguarda-marco"} & labels)
    blocked = pending or any(not completed(dep) for dep in issue["blockedBy"]["nodes"])
    if status in {"Em andamento", "Em revisão"} and blocked:
        print(f"Aviso: #{issue['number']} tem bloqueio. Estado preservado: {status}.")
    if status == "Pronta" and blocked:
        return "Backlog"
    if status in {None, "Backlog", "Concluída"}:
        target = "Pronta" if "triada" in labels and not blocked else "Backlog"
        return target if status != target else None
    return None


def main():
    if os.getenv("GITHUB_ACTIONS") == "true" and not os.getenv("GH_TOKEN"):
        raise SystemExit("Configure o secret BUDDYUP_PROJECT_TOKEN no GitHub.")
    apply = os.getenv("APPLY_CHANGES", "").lower() == "true"
    after = None
    items = []
    while True:
        user = graphql(QUERY, after=after)["user"]
        project = user and user["projectV2"]
        if not project or project["title"] != "BuddyUp" or not project["field"]:
            raise SystemExit("Quadro BuddyUp ou campo Status indisponível.")
        page = project["items"]
        items.extend(page["nodes"])
        if not page["pageInfo"]["hasNextPage"]:
            break
        after = page["pageInfo"]["endCursor"]

    options = {option["name"]: option["id"] for option in project["field"]["options"]}
    if not {"Backlog", "Pronta", "Em andamento", "Em revisão", "Concluída"} <= options.keys():
        raise SystemExit("Os estados esperados não estão configurados no quadro.")
    count = 0
    for item in items:
        target = next_status(item)
        if not target:
            continue
        if apply:
            current = graphql(RECHECK, id=item["id"])["node"]
            if not current or current.get("fieldValueByName") != item.get("fieldValueByName"):
                continue
            if next_status(current) != target:
                continue
            graphql(UPDATE, input={"projectId": project["id"], "itemId": item["id"],
                                  "fieldId": project["field"]["id"],
                                  "value": {"singleSelectOptionId": options[target]}})
        count += 1
        print(f"#{item['content']['number']} → {target}")
    print(f"{len(items)} cartões conferidos. {count} mudanças {'aplicadas' if apply else 'previstas'}.")


if __name__ == "__main__":
    main()
