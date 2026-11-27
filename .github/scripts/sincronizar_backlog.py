"""Atualiza apenas os cartões do BuddyUp. Por padrão, mostra uma prévia."""
import json
import os
import subprocess

REPOSITORY = "lucasccesar/BuddyUp"
FIELDS = """
    id isArchived
    fieldValueByName(name: "Status") { ... on ProjectV2ItemFieldSingleSelectValue { name } }
    content { ... on Issue {
        id number state stateReason repository { nameWithOwner }
        assignees { totalCount }
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
ADD = """
mutation($project:ID!, $content:ID!) {
  addProjectV2ItemById(input:{projectId:$project, contentId:$content}) {
    item { id isArchived fieldValueByName(name:"Status") {
      ... on ProjectV2ItemFieldSingleSelectValue { name }
    } }
  }
}
"""
REPOSITORY_QUERY = """
query($after:String) {
  repository(owner:"lucasccesar", name:"BuddyUp") {
    CONNECTION(states:OPEN, first:25, after:$after) {
      pageInfo { hasNextPage endCursor }
      nodes { SELECTION }
    }
  }
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


def repository_nodes(connection, fields):
    query = REPOSITORY_QUERY.replace("CONNECTION", connection).replace("SELECTION", fields)
    after = None
    while True:
        page = graphql(query, after=after)["repository"][connection]
        yield from page["nodes"]
        if not page["pageInfo"]["hasNextPage"]:
            break
        after = page["pageInfo"]["endCursor"]


def repository_cards():
    issues = list(repository_nodes("issues", "id number"))
    reviews = {}
    prs = repository_nodes("pullRequests", """
        isCrossRepository baseRefName isDraft
        closingIssuesReferences(first:25) { nodes { number repository { nameWithOwner } } }
    """)
    for pr in prs:
        if pr["isCrossRepository"] or pr["baseRefName"] != "main":
            continue
        for issue in pr["closingIssuesReferences"]["nodes"]:
            if issue["repository"]["nameWithOwner"] == REPOSITORY:
                number = issue["number"]
                if not pr["isDraft"]:
                    reviews[number] = "ready"
                else:
                    reviews.setdefault(number, "draft")
    return issues, reviews


def next_status(item, reviews=None):
    issue = item.get("content") or {}
    status = (item.get("fieldValueByName") or {}).get("name")
    if item["isArchived"] or issue.get("repository", {}).get("nameWithOwner") != REPOSITORY:
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
        target = "Backlog" if blocked else "Pronta"
        return target if status != target else None
    if blocked:
        return None
    review = (reviews or {}).get(issue["number"])
    ready = review == "ready" or "revisar" in labels
    if status in {"Pronta", "Em andamento"} and ready:
        return "Em revisão"
    if status == "Em revisão" and review == "draft" and not ready:
        return "Em andamento"
    if status == "Pronta" and (issue["assignees"]["totalCount"] or review == "draft"):
        return "Em andamento"
    return None


def set_status(project, item_id, option):
    graphql(UPDATE, input={"projectId": project["id"], "itemId": item_id,
                           "fieldId": project["field"]["id"],
                           "value": {"singleSelectOptionId": option}})


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
    issues, reviews = repository_cards()
    existing = {(item.get("content") or {}).get("id") for item in items}
    count = 0
    for issue in issues:
        if issue["id"] in existing:
            continue
        if apply:
            item = graphql(ADD, project=project["id"], content=issue["id"])["addProjectV2ItemById"]["item"]
            if item["isArchived"] or item.get("fieldValueByName"):
                continue
            set_status(project, item["id"], options["Backlog"])
        count += 1
        print(f"#{issue['number']} → Backlog (novo cartão)")
    for item in items:
        target = next_status(item, reviews)
        if not target:
            continue
        if apply:
            current = graphql(RECHECK, id=item["id"])["node"]
            if not current or current.get("fieldValueByName") != item.get("fieldValueByName"):
                continue
            if next_status(current, reviews) != target:
                continue
            set_status(project, item["id"], options[target])
        count += 1
        print(f"#{item['content']['number']} → {target}")
    print(f"{len(items)} cartões conferidos. {count} mudanças {'aplicadas' if apply else 'previstas'}.")


if __name__ == "__main__":
    main()
