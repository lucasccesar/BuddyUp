import unittest
from unittest.mock import patch

import sincronizar_backlog
from sincronizar_backlog import next_status


def card(status, *, assigned=False, labels=(), dependencies=(), closed=False):
    def connection(nodes):
        return {"pageInfo": {"hasNextPage": False}, "nodes": list(nodes)}
    return {
        "isArchived": False,
        "fieldValueByName": {"name": status},
        "content": {
            "number": 42, "state": "CLOSED" if closed else "OPEN",
            "stateReason": "COMPLETED" if closed else None,
            "repository": {"nameWithOwner": "lucasccesar/BuddyUp"},
            "labels": connection({"name": label} for label in labels),
            "assignees": {"totalCount": int(assigned)},
            "blockedBy": connection(dependencies),
        },
    }


class StatusTests(unittest.TestCase):
    def test_issue_sem_dependencias_fica_pronta(self):
        self.assertEqual(next_status(card("Backlog")), "Pronta")

    def test_issue_fica_pronta_quando_a_dependencia_e_concluida(self):
        item = card("Backlog", dependencies=[{"state": "CLOSED", "stateReason": "COMPLETED"}])
        self.assertEqual(next_status(item), "Pronta")

    def test_dependencia_fechada_sem_conclusao_mantem_backlog(self):
        item = card("Backlog", dependencies=[{"state": "CLOSED", "stateReason": "NOT_PLANNED"}])
        self.assertIsNone(next_status(item))

    def test_pendencias_e_epicos_mantem_backlog(self):
        for label in ("aguarda-decisão", "aguarda-marco", "épico"):
            with self.subTest(label=label):
                self.assertIsNone(next_status(card("Backlog", labels=[label])))

    def test_atribuicao_inicia_issue_pronta(self):
        self.assertEqual(next_status(card("Pronta", assigned=True)), "Em andamento")

    def test_atribuicao_nao_libera_dependencia_aberta(self):
        item = card("Backlog", assigned=True, dependencies=[{"state": "OPEN", "stateReason": None}])
        self.assertIsNone(next_status(item))

    def test_pr_pronto_move_para_revisao(self):
        self.assertEqual(next_status(card("Em andamento"), {42: "ready"}), "Em revisão")

    def test_rascunho_nao_move_para_revisao(self):
        self.assertIsNone(next_status(card("Em andamento"), {42: "draft"}))

    def test_rascunho_devolve_para_em_andamento(self):
        self.assertEqual(next_status(card("Em revisão"), {42: "draft"}), "Em andamento")

    def test_etiqueta_permite_revisao_sem_pr(self):
        self.assertEqual(next_status(card("Em andamento", labels=["revisar"])), "Em revisão")

    def test_pr_pronto_nao_move_issue_bloqueada(self):
        item = card("Em andamento", dependencies=[{"state": "OPEN", "stateReason": None}])
        self.assertIsNone(next_status(item, {42: "ready"}))

    def test_issue_concluida_mantem_fluxo_existente(self):
        self.assertEqual(next_status(card("Em revisão", closed=True)), "Concluída")


class PullRequestTests(unittest.TestCase):
    @patch("sincronizar_backlog.graphql")
    def test_so_pr_do_repositorio_com_vinculo_de_fechamento(self, graphql):
        ref = {"number": 42, "repository": {"nameWithOwner": "lucasccesar/BuddyUp"}}
        prs = [
            {"isCrossRepository": False, "baseRefName": "main", "isDraft": True,
             "closingIssuesReferences": {"nodes": [ref]}},
            {"isCrossRepository": True, "baseRefName": "main", "isDraft": False,
             "closingIssuesReferences": {"nodes": [ref]}},
            {"isCrossRepository": False, "baseRefName": "main", "isDraft": False,
             "closingIssuesReferences": {"nodes": []}},
        ]
        graphql.side_effect = [
            {"repository": {"issues": {"nodes": [{"id": "I_42", "number": 42}],
                                       "pageInfo": {"hasNextPage": False}}}},
            {"repository": {"pullRequests": {"nodes": prs, "pageInfo": {"hasNextPage": False}}}},
        ]
        issues, reviews = sincronizar_backlog.repository_cards()
        self.assertEqual(issues, [{"id": "I_42", "number": 42}])
        self.assertEqual(reviews, {42: "draft"})
